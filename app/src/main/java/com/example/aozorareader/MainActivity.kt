package com.example.aozorareader

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.room.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipInputStream

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
    @Query("SELECT * FROM books WHERE isDownloaded = 1 ORDER BY id DESC")
    fun getDownloadedBooks(): Flow<List<Book>>

    @Query("SELECT * FROM books WHERE title LIKE '%' || :query || '%' OR author LIKE '%' || :query || '%' ORDER BY title ASC LIMIT 100")
    suspend fun searchLocal(query: String): List<Book>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(book: Book)

    @Query("SELECT * FROM books WHERE id = :id LIMIT 1")
    suspend fun getBookById(id: Int): Book?
}

@Database(entities = [Book::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
}

// ==========================================
// 2. リポジトリ (厳密UTF-8/Windows-31J 自動判定 & 完全ローカル検索)
// ==========================================
class BookRepository(private val context: Context, private val bookDao: BookDao) {
    private val client = OkHttpClient()

    private fun extractTxt(bytes: ByteArray): ByteArray? {
        return try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (entry.name.endsWith(".txt", ignoreCase = true)) {
                        return zis.readBytes()
                    }
                    entry = zis.nextEntry
                }
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    suspend fun search(query: String): List<Book> = withContext(Dispatchers.IO) {
        val q = query.replace(" ", "").replace("　", "")
        if (q.isBlank()) return@withContext emptyList()
        bookDao.searchLocal(q)
    }

    suspend fun downloadBook(book: Book): Book? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(book.downloadUrl).build()
            client.newCall(request).execute().use { res ->
                if (!res.isSuccessful) return@use null
                val bytes = res.body?.bytes() ?: return@use null

                val raw = (if (book.downloadUrl.endsWith(".zip", ignoreCase = true)) extractTxt(bytes) else bytes)
                    ?: return@use null

                // 厳格なUTF-8デコードを先に試行し、失敗時はwindows-31j (Shift_JIS外字) へフォールバック
                val rawText = try {
                    Charset.forName("UTF-8").newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(raw))
                        .toString()
                } catch (e: Exception) {
                    String(raw, Charset.forName("windows-31j"))
                }

                val file = File(context.filesDir, "book_${book.id}.txt")
                file.writeText(rawText, Charsets.UTF_8)

                val updated = book.copy(isDownloaded = true, localFilePath = file.absolutePath)
                bookDao.insertOrUpdate(updated)
                updated
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}

// ==========================================
// 3. 青空文庫パーサー
// ==========================================
object AozoraParser {
    fun toHtml(rawText: String, initialFontSize: Int, initialDarkMode: Boolean): String {
        var text = rawText

        // ① 先頭の記号・凡例ブロック除去（説明文がある場合のみ安全に除去）
        if (text.contains("【テキスト中に現れる記号について】")) {
            val headerRegex = Regex("""(?s)^.*?-------------------------------------------------------.*?-------------------------------------------------------[\r\n]*""")
            text = text.replace(headerRegex, "")
        }

        // ② 末尾の「底本：」以降を除去
        val bottomIdx = text.lastIndexOf("底本：")
        if (bottomIdx != -1) {
            text = text.substring(0, bottomIdx)
        }

        // ③ HTMLエスケープ
        text = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

        // ④ 外字・注記の除去 (※［＃...］および［＃...］)
        text = text.replace(Regex("※?［＃.*?］"), "")

        // ⑤ ルビ変換 (｜付きの親文字 ➔ 単独漢字ルビ)
        text = text.replace(Regex("｜([^《》\\n]+)《([^》\\n]+)》")) { "<ruby>${it.groupValues[1]}<rt>${it.groupValues[2]}</rt></ruby>" }
        text = text.replace(Regex("([一-龠々〆ヵヶ]+)《([^》\\n]+)》")) { "<ruby>${it.groupValues[1]}<rt>${it.groupValues[2]}</rt></ruby>" }

        // ⑥ 改行
        text = text.replace("\n", "<br>")

        val bgColor = if (initialDarkMode) "#121212" else "#faf7f0"
        val textColor = if (initialDarkMode) "#e0e0e0" else "#2b2b2b"
        val rubyColor = if (initialDarkMode) "#888888" else "#666666"

        return """
        <!DOCTYPE html>
        <html>
        <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
            <style>
                html {
                    background-color: $bgColor;
                    height: 100%;
                }
                body {
                    margin: 0;
                    padding: 80px 24px 24px 24px;
                    box-sizing: border-box;
                    height: 100%;
                    background-color: $bgColor;
                    color: $textColor;
                    font-family: 'Noto Serif CJK JP', 'Noto Serif JP', 'Hiragino Mincho ProN', 'Yu Mincho', serif;
                    font-size: ${initialFontSize}px;
                    line-height: 1.9;
                    writing-mode: vertical-rl;
                    -webkit-writing-mode: vertical-rl;
                    overflow-x: scroll;
                    overflow-y: hidden;
                    transition: background-color 0.25s, color 0.25s;
                }
                ruby rt { font-size: 0.5em; color: $rubyColor; }
            </style>
            <script>
                function updateTheme(isDark) {
                    var bg = isDark ? '#121212' : '#faf7f0';
                    var fg = isDark ? '#e0e0e0' : '#2b2b2b';
                    document.documentElement.style.backgroundColor = bg;
                    document.body.style.backgroundColor = bg;
                    document.body.style.color = fg;
                    var rts = document.querySelectorAll('ruby rt');
                    for (var i = 0; i < rts.length; i++) { rts[i].style.color = isDark ? '#888888' : '#666666'; }
                }
                function updateFontSize(size) { document.body.style.fontSize = size + 'px'; }
            </script>
        </head>
        <body>$text</body>
        </html>
        """.trimIndent()
    }
}

// ==========================================
// 4. UI: リーダー画面
// ==========================================
@SuppressLint("ClickableViewAccessibility")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(book: Book, onBack: () -> Unit) {
    BackHandler(onBack = onBack)

    var fontSize by rememberSaveable { mutableIntStateOf(18) }
    var isDarkMode by rememberSaveable { mutableStateOf(false) }
    var showControls by remember { mutableStateOf(true) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    val htmlContent by produceState<String?>(initialValue = null, key1 = book.localFilePath) {
        value = withContext(Dispatchers.IO) {
            val path = book.localFilePath
            if (path != null && File(path).exists()) {
                AozoraParser.toHtml(File(path).readText(Charsets.UTF_8), fontSize, isDarkMode)
            } else {
                "<html><body><p>作品を読み込めませんでした</p></body></html>"
            }
        }
    }

    LaunchedEffect(isDarkMode) { webViewRef?.evaluateJavascript("updateTheme($isDarkMode);", null) }
    LaunchedEffect(fontSize) { webViewRef?.evaluateJavascript("updateFontSize($fontSize);", null) }

    Box(modifier = Modifier.fillMaxSize()) {
        if (htmlContent == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    WebView(context).apply {
                        webViewClient = WebViewClient()
                        settings.apply {
                            javaScriptEnabled = true
                            loadWithOverviewMode = true
                            useWideViewPort = true
                        }
                        val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
                            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                                showControls = !showControls
                                return true
                            }
                        })
                        setOnTouchListener { _, event ->
                            gestureDetector.onTouchEvent(event)
                            false
                        }
                        loadDataWithBaseURL(null, htmlContent!!, "text/html", "utf-8", null)
                        webViewRef = this
                    }
                }
            )

            DisposableEffect(Unit) {
                onDispose {
                    webViewRef?.destroy()
                }
            }
        }

        AnimatedVisibility(
            visible = showControls,
            enter = slideInVertically { -it },
            exit = slideOutVertically { -it },
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            TopAppBar(
                title = { Text(book.title, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                },
                actions = {
                    IconButton(onClick = { if (fontSize > 12) fontSize -= 2 }, enabled = fontSize > 12) {
                        Icon(Icons.Default.TextDecrease, contentDescription = "文字縮小")
                    }
                    IconButton(onClick = { if (fontSize < 32) fontSize += 2 }, enabled = fontSize < 32) {
                        Icon(Icons.Default.TextIncrease, contentDescription = "文字拡大")
                    }
                    IconButton(onClick = { isDarkMode = !isDarkMode }) {
                        Icon(if (isDarkMode) Icons.Default.LightMode else Icons.Default.DarkMode, contentDescription = "テーマ")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f))
            )
        }
    }
}

// ==========================================
// 5. UI: メイン画面
// ==========================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    downloadedBooks: List<Book>,
    searchResults: List<Book>,
    searchQuery: String,
    isSearching: Boolean,
    hasSearched: Boolean,
    onQueryChange: (String) -> Unit,
    onSearchSubmit: () -> Unit,
    onDownloadClick: (Book) -> Unit,
    onBookClick: (Book) -> Unit
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(if (selectedTab == 0) "本棚（オフライン）" else "青空文庫 全作品検索") })
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
                if (downloadedBooks.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("保存された作品がありません。\n「探す」タブから本を検索して保存してください。")
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(downloadedBooks, key = { it.id }) { book ->
                            ListItem(
                                headlineContent = { Text(book.title) },
                                supportingContent = { Text(book.author) },
                                trailingContent = {
                                    FilledTonalButton(onClick = { onBookClick(book) }) { Text("読む") }
                                },
                                modifier = Modifier.clickable { onBookClick(book) }
                            )
                            HorizontalDivider()
                        }
                    }
                }
            } else {
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = onQueryChange,
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("作品名・作家名（例: 漱石、こころ）") },
                            singleLine = true
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(onClick = onSearchSubmit) {
                            Text("検索")
                        }
                    }

                    if (isSearching) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    } else if (hasSearched && searchResults.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("該当する作品が見つかりませんでした。\n別のキーワードでお試しください。")
                        }
                    } else if (!hasSearched) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("作家名や作品名を入力して検索してください。\n（全1.8万作品からオフラインで即座に検索できます）")
                        }
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(searchResults, key = { it.id }) { book ->
                                ListItem(
                                    headlineContent = { Text(book.title) },
                                    supportingContent = { Text(book.author) },
                                    trailingContent = {
                                        if (book.isDownloaded) {
                                            TextButton(onClick = { onBookClick(book) }) { Text("読む") }
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
}

// ==========================================
// 6. エントリーポイント
// ==========================================
class MainActivity : ComponentActivity() {
    private val db by lazy {
        Room.databaseBuilder(applicationContext, AppDatabase::class.java, "aozora_official.db")
            .createFromAsset("databases/aozora_official.db")
            .build()
    }
    private val repository by lazy { BookRepository(this, db.bookDao()) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                var currentReadingBook by remember { mutableStateOf<Book?>(null) }
                var searchQuery by rememberSaveable { mutableStateOf("") }
                var searchResults by remember { mutableStateOf<List<Book>>(emptyList()) }
                var isSearching by remember { mutableStateOf(false) }
                var hasSearched by rememberSaveable { mutableStateOf(false) }

                val downloadedBooksFlow = remember { db.bookDao().getDownloadedBooks() }
                val downloadedBooks by downloadedBooksFlow.collectAsState(initial = emptyList())
                val scope = rememberCoroutineScope()
                val snackbarHostState = remember { SnackbarHostState() }

                Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
                    Box(modifier = Modifier.padding(padding)) {
                        if (currentReadingBook != null) {
                            ReaderScreen(
                                book = currentReadingBook!!,
                                onBack = { currentReadingBook = null }
                            )
                        } else {
                            MainScreen(
                                downloadedBooks = downloadedBooks,
                                searchResults = searchResults,
                                searchQuery = searchQuery,
                                isSearching = isSearching,
                                hasSearched = hasSearched,
                                onQueryChange = { searchQuery = it },
                                onSearchSubmit = {
                                    scope.launch {
                                        isSearching = true
                                        hasSearched = true
                                        searchResults = repository.search(searchQuery)
                                        isSearching = false
                                    }
                                },
                                onDownloadClick = { book ->
                                    scope.launch {
                                        val updated = repository.downloadBook(book)
                                        if (updated != null) {
                                            searchResults = searchResults.map { if (it.id == book.id) updated else it }
                                            snackbarHostState.showSnackbar("『${book.title}』をオフラインに保存しました")
                                        } else {
                                            snackbarHostState.showSnackbar("ダウンロードに失敗しました")
                                        }
                                    }
                                },
                                onBookClick = { book -> currentReadingBook = book }
                            )
                        }
                    }
                }
            }
        }
    }
}
