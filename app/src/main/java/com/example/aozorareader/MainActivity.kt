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
import org.json.JSONArray
import java.io.File
import java.net.URLEncoder

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
    @Query("SELECT * FROM books WHERE isDownloaded = 1")
    fun getDownloadedBooks(): Flow<List<Book>>

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
// 2. リポジトリ (全作品API検索 & ダウンロード)
// ==========================================
class BookRepository(private val context: Context, private val bookDao: BookDao) {
    private val client = OkHttpClient()

    // 青空文庫の全作品検索API
    suspend fun searchOnline(query: String): List<Book> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        try {
            val encodedQuery = URLEncoder.encode(query.trim(), "UTF-8")
            val url = "https://api.bungo.app/v1/books?q=$encodedQuery&limit=30"
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()
            val jsonStr = response.body?.string() ?: return@withContext emptyList()

            val jsonArray = JSONArray(jsonStr)
            val result = mutableListOf<Book>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val id = obj.getInt("id")
                val title = obj.getString("title")
                val author = if (obj.has("author_name")) obj.getString("author_name") else "不明"
                val textUrl = "https://api.bungo.app/v1/books/$id/text"

                // 端末にすでに保存済みかチェック
                val existing = bookDao.getBookById(id)
                if (existing != null && existing.isDownloaded) {
                    result.add(existing)
                } else {
                    result.add(Book(id, title, author, textUrl, null, false))
                }
            }
            result
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    // 本文ダウンロード
    suspend fun downloadBook(book: Book): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(book.downloadUrl).build()
            val response = client.newCall(request).execute()
            val text = response.body?.string() ?: return@withContext false

            val file = File(context.filesDir, "book_${book.id}.txt")
            file.writeText(text, Charsets.UTF_8)
            bookDao.insertOrUpdate(book.copy(isDownloaded = true, localFilePath = file.absolutePath))
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}

// ==========================================
// 3. 青空文庫パーサー
// ==========================================
object AozoraParser {
    fun toHtml(rawText: String, initialFontSize: Int, initialDarkMode: Boolean): String {
        var text = rawText
        // ルビ記法変換
        text = text.replace(Regex("(?:｜)?([一-龠々〆ヵヶ]+)《([^》]+)》")) { match ->
            "<ruby>${match.groupValues[1]}<rt>${match.groupValues[2]}</rt></ruby>"
        }
        text = text.replace(Regex("［＃.*?］"), "")
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
// 4. UI: リーダー画面
// ==========================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(title: String, filePath: String, onBack: () -> Unit) {
    var fontSize by remember { mutableIntStateOf(18) }
    var isDarkMode by remember { mutableStateOf(false) }
    var showControls by remember { mutableStateOf(true) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    val htmlContent = remember(filePath) {
        val file = File(filePath)
        if (file.exists()) {
            AozoraParser.toHtml(file.readText(Charsets.UTF_8), fontSize, isDarkMode)
        } else {
            "<html><body><p>作品を読み込めませんでした</p></body></html>"
        }
    }

    LaunchedEffect(isDarkMode) { webViewRef?.evaluateJavascript("updateTheme($isDarkMode);", null) }
    LaunchedEffect(fontSize) { webViewRef?.evaluateJavascript("updateFontSize($fontSize);", null) }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                    showControls = !showControls
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

        AnimatedVisibility(
            visible = showControls,
            enter = slideInVertically { -it },
            exit = slideOutVertically { -it },
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
// 5. UI: メイン画面 (全作品検索対応)
// ==========================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    downloadedBooks: List<Book>,
    searchResults: List<Book>,
    searchQuery: String,
    isSearching: Boolean,
    onQueryChange: (String) -> Unit,
    onSearchSubmit: () -> Unit,
    onDownloadClick: (Book) -> Unit,
    onBookClick: (Book) -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }

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
                        Text("保存された作品がありません。\n「探す」タブから好きな本を検索して保存してください。")
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(downloadedBooks) { book ->
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
                            placeholder = { Text("作品名・著者名（例: 漱石、メロス）") },
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
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(searchResults) { book ->
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
    private val db by lazy { Room.databaseBuilder(applicationContext, AppDatabase::class.java, "aozora.db").build() }
    private val repository by lazy { BookRepository(this, db.bookDao()) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                var currentReadingBook by remember { mutableStateOf<Book?>(null) }
                var searchQuery by remember { mutableStateOf("") }
                var searchResults by remember { mutableStateOf<List<Book>>(emptyList()) }
                var isSearching by remember { mutableStateOf(false) }

                val downloadedBooks by db.bookDao().getDownloadedBooks().collectAsState(initial = emptyList())
                val scope = rememberCoroutineScope()
                val snackbarHostState = remember { SnackbarHostState() }

                Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { _ ->
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
                            isSearching = isSearching,
                            onQueryChange = { searchQuery = it },
                            onSearchSubmit = {
                                scope.launch {
                                    isSearching = true
                                    searchResults = repository.searchOnline(searchQuery)
                                    isSearching = false
                                }
                            },
                            onDownloadClick = { book ->
                                scope.launch {
                                    val success = repository.downloadBook(book)
                                    if (success) {
                                        // 検索結果側のダウンロード済みフラグも更新
                                        searchResults = searchResults.map {
                                            if (it.id == book.id) it.copy(isDownloaded = true) else it
                                        }
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
