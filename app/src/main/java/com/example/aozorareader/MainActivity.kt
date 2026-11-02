package com.example.aozorareader

import android.content.Context
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import androidx.room.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.nio.charset.Charset

// ==========================================
// 1. データ構造 & Room DB
// ==========================================
@Entity(tableName = "books")
data class Book(
    @PrimaryKey val id: Int,
    val title: String,
    val author: String,
    val downloadUrl: String,
    val localFilePath: String? = null,
    val isDownloaded: Boolean = false
)

@Dao
interface BookDao {
    @Query("SELECT * FROM books WHERE title LIKE '%' || :query || '%' OR author LIKE '%' || :query || '%'")
    fun searchBooks(query: String): Flow<List<Book>>

    @Query("SELECT * FROM books WHERE isDownloaded = 1")
    fun getDownloadedBooks(): Flow<List<Book>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(books: List<Book>)

    @Update
    suspend fun updateBook(book: Book)
}

@Database(entities = [Book::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
}

// ==========================================
// 2. リポジトリ (通信 & キャッシュ)
// ==========================================
class BookRepository(private val context: Context, private val bookDao: BookDao) {
    private val client = OkHttpClient()

    suspend fun seedInitialData() = withContext(Dispatchers.IO) {
        val initialBooks = listOf(
            Book(1, "走れメロス", "太宰治", "https://raw.githubusercontent.com/aozorabunko/aozorabunko/master/cards/000035/files/1567_ruby_4948.txt"),
            Book(2, "羅生門", "芥川龍之介", "https://raw.githubusercontent.com/aozorabunko/aozorabunko/master/cards/000879/files/127_ruby_150.txt"),
            Book(3, "坊っちゃん", "夏目漱石", "https://raw.githubusercontent.com/aozorabunko/aozorabunko/master/cards/000148/files/752_ruby_2438.txt"),
            Book(4, "銀河鉄道の夜", "宮沢賢治", "https://raw.githubusercontent.com/aozorabunko/aozorabunko/master/cards/000081/files/456_ruby_145.txt")
        )
        bookDao.insertAll(initialBooks)
    }

    suspend fun downloadBook(book: Book): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(book.downloadUrl).build()
            val response = client.newCall(request).execute()
            val bytes = response.body?.bytes() ?: return@withContext false

            // Shift_JIS (MS932) または UTF-8 自動判別
            val rawText = try {
                String(bytes, Charset.forName("MS932"))
            } catch (e: Exception) {
                String(bytes, Charsets.UTF_8)
            }

            val file = File(context.filesDir, "book_${book.id}.txt")
            file.writeText(rawText, Charsets.UTF_8)
            bookDao.updateBook(book.copy(isDownloaded = true, localFilePath = file.absolutePath))
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}

// ==========================================
// 3. 青空文庫パーサー (ルビ・縦書きHTML生成)
// ==========================================
object AozoraParser {
    fun toHtml(rawText: String, initialFontSize: Int, initialDarkMode: Boolean): String {
        var text = rawText
        // 青空文庫ルビ記法: ｜漢字《かんじ》 または 漢字《かんじ》
        text = text.replace(Regex("(?:｜)?([一-龠々〆ヵヶ]+)《([^》]+)》")) { match ->
            "<ruby>${match.groupValues[1]}<rt>${match.groupValues[2]}</rt></ruby>"
        }
        // 注記の除去
        text = text.replace(Regex("［＃.*?］"), "")
        // 改行処理
        text = text.replace("\n", "<br>")

        val bgColor = if (initialDarkMode) "#121212" else "#faf7f0"
        val textColor = if (initialDarkMode) "#e0e0e0" else "#2b2b2b"
        val rubyColor = if (initialDarkMode) "#888888" else "#666666"

        return """
        <!DOCTYPE html>
        <html>
        <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0, user-scalable=no">
            <style>
                html, body {
                    margin: 0;
                    padding: 24px;
                    height: 100%;
                    background-color: $bgColor;
                    color: $textColor;
                    font-family: 'Hiragino Mincho ProN', 'Yu Mincho', serif;
                    font-size: ${initialFontSize}px;
                    line-height: 1.9;
                    writing-mode: vertical-rl;
                    -webkit-writing-mode: vertical-rl;
                    overflow-x: scroll;
                    overflow-y: hidden;
                    transition: background-color 0.25s, color 0.25s;
                }
                ruby rt {
                    font-size: 0.5em;
                    color: $rubyColor;
                }
            </style>
            <script>
                function updateTheme(isDark) {
                    document.body.style.backgroundColor = isDark ? '#121212' : '#faf7f0';
                    document.body.style.color = isDark ? '#e0e0e0' : '#2b2b2b';
                    var rts = document.querySelectorAll('ruby rt');
                    for (var i = 0; i < rts.length; i++) {
                        rts[i].style.color = isDark ? '#888888' : '#666666';
                    }
                }
                function updateFontSize(size) {
                    document.body.style.fontSize = size + 'px';
                }
            </script>
        </head>
        <body>
            $text
        </body>
        </html>
        """.trimIndent()
    }
}

// ==========================================
// 4. UI: 読書ビューア画面 (縦書き・拡大・ダークモード)
// ==========================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    title: String,
    filePath: String,
    onBack: () -> Unit
) {
    var fontSize by remember { mutableIntStateOf(18) } // デフォルト 18px
    var isDarkMode by remember { mutableStateOf(false) }
    var showControls by remember { mutableStateOf(true) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    val htmlContent = remember(filePath) {
        val file = File(filePath)
        if (file.exists()) {
            AozoraParser.toHtml(file.readText(Charsets.UTF_8), fontSize, isDarkMode)
        } else {
            "<html><body><p>ファイルが見つかりません</p></body></html>"
        }
    }

    // スタイル変更をJavaScriptで即座に反映（スクロール位置をリセットさせない）
    LaunchedEffect(isDarkMode) {
        webViewRef?.evaluateJavascript("updateTheme($isDarkMode);", null)
    }
    LaunchedEffect(fontSize) {
        webViewRef?.evaluateJavascript("updateFontSize($fontSize);", null)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // 本文 WebView
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) {
                    showControls = !showControls // タップでメニュー表示切替
                },
            factory = { context ->
                WebView(context).apply {
                    webViewClient = WebViewClient()
                    settings.apply {
                        javaScriptEnabled = true
                        loadWithOverviewMode = true
                        useWideViewPort = true
                    }
                    loadDataWithBaseURL(null, htmlContent, "text/html", "utf-8", null)
                    webViewRef = this
                }
            }
        )

        // 上部ツールバー (タップで出入り)
        AnimatedVisibility(
            visible = showControls,
            enter = slideInVertically(initialOffsetY = { -it }),
            exit = slideOutVertically(targetOffsetY = { -it }),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            TopAppBar(
                title = { Text(title, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                },
                actions = {
                    // 文字縮小
                    IconButton(
                        onClick = { if (fontSize > 12) fontSize -= 2 },
                        enabled = fontSize > 12
                    ) {
                        Icon(Icons.Default.TextDecrease, contentDescription = "文字縮小")
                    }
                    // 文字拡大
                    IconButton(
                        onClick = { if (fontSize < 32) fontSize += 2 },
                        enabled = fontSize < 32
                    ) {
                        Icon(Icons.Default.TextIncrease, contentDescription = "文字拡大")
                    }
                    // ダークモード切り替え
                    IconButton(onClick = { isDarkMode = !isDarkMode }) {
                        Icon(
                            if (isDarkMode) Icons.Default.LightMode else Icons.Default.DarkMode,
                            contentDescription = "テーマ切り替え"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)
                )
            )
        }
    }
}

// ==========================================
// 5. UI: メイン画面 (本棚 & 検索タブ)
// ==========================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    downloadedBooks: List<Book>,
    searchResults: List<Book>,
    searchQuery: String,
    onQueryChange: (String) -> Unit,
    onDownloadClick: (Book) -> Unit,
    onBookClick: (Book) -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (selectedTab == 0) "本棚（オフライン）" else "作品を探す") }
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.MenuBook, contentDescription = null) },
                    label = { Text("本棚") }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Default.Search, contentDescription = null) },
                    label = { Text("探す") }
                )
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            if (selectedTab == 0) {
                // 本棚タブ
                if (downloadedBooks.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("保存された本がありません。\n「探す」タブからダウンロードしてください。")
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(downloadedBooks) { book ->
                            ListItem(
                                headlineContent = { Text(book.title) },
                                supportingContent = { Text(book.author) },
                                trailingContent = {
                                    FilledTonalButton(onClick = { onBookClick(book) }) {
                                        Text("読む")
                                    }
                                },
                                modifier = Modifier.clickable { onBookClick(book) }
                            )
                            HorizontalDivider()
                        }
                    }
                }
            } else {
                // 探すタブ
                Column(modifier = Modifier.fillMaxSize()) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = onQueryChange,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        placeholder = { Text("作品名・著者名で検索") },
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) }
                    )
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(searchResults) { book ->
                            ListItem(
                                headlineContent = { Text(book.title) },
                                supportingContent = { Text(book.author) },
                                trailingContent = {
                                    if (book.isDownloaded) {
                                        TextButton(onClick = { onBookClick(book) }) {
                                            Text("読む")
                                        }
                                    } else {
                                        IconButton(onClick = { onDownloadClick(book) }) {
                                            Icon(Icons.Default.Download, contentDescription = "ダウンロード")
                                        }
                                    }
                                }
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

// ==========================================
// 6. エントリーポイント (Activity)
// ==========================================
class MainActivity : ComponentActivity() {
    private val db by lazy {
        Room.databaseBuilder(applicationContext, AppDatabase::class.java, "aozora.db").build()
    }
    private val repository by lazy { BookRepository(this, db.bookDao()) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        lifecycleScope.launch {
            repository.seedInitialData()
        }

        setContent {
            MaterialTheme {
                var currentReadingBook by remember { mutableStateOf<Book?>(null) }
                var searchQuery by remember { mutableStateOf("") }

                val downloadedBooks by db.bookDao().getDownloadedBooks().collectAsState(initial = emptyList())
                val searchResults by db.bookDao().searchBooks(searchQuery).collectAsState(initial = emptyList())
                val scope = rememberCoroutineScope()
                val snackbarHostState = remember { SnackbarHostState() }

                Scaffold(
                    snackbarHost = { SnackbarHost(snackbarHostState) }
                ) { _ ->
                    if (currentReadingBook != null) {
                        ReaderScreen(
                            title = currentReadingBook!!.title,
                            filePath = currentReadingBook!!.localFilePath ?: "",
                            onBack = { currentReadingBook = null }
                        )
                    } else {
                        MainScreen(
                            downloadedBooks = downloadedBooks,
                            searchResults = searchResults,
                            searchQuery = searchQuery,
                            onQueryChange = { searchQuery = it },
                            onDownloadClick = { book ->
                                scope.launch {
                                    val success = repository.downloadBook(book)
                                    if (success) {
                                        snackbarHostState.showSnackbar("『${book.title}』をダウンロードしました")
                                    } else {
                                        snackbarHostState.showSnackbar("ダウンロードに失敗しました")
                                    }
                                }
                            },
                            onBookClick = { book ->
                                currentReadingBook = book
                            }
                        )
                    }
                }
            }
        }
    }
}
