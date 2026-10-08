package com.blockyou.firestick

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.kiosk.KioskInfo
import org.schabi.newpipe.extractor.linkhandler.SearchQueryHandler
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.util.concurrent.Executors

enum class NavKind { HOME, SHELVES, GRID }

/** Item do menu lateral: [id] é o browseId da página (null = Início). */
data class NavEntry(val id: String?, val titleRes: Int, val iconRes: Int, val kind: NavKind)

/**
 * Tela inicial. Dois modos:
 * - Home: fileiras horizontais com as listas em alta do YouTube;
 * - Busca: grade com os resultados (Voltar retorna à Home).
 */
class MainActivity : Activity() {
    private lateinit var searchInput: EditText
    private lateinit var homeRows: RecyclerView
    private lateinit var results: RecyclerView
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView

    private val executor = Executors.newFixedThreadPool(4)
    private val searchAdapter = VideoAdapter(onClick = ::openVideo, onNearEnd = ::loadMore)
    private lateinit var rowAdapter: RowAdapter

    // Fileiras já carregadas, para inserir cada nova na posição certa
    private val loadedKiosks = mutableSetOf<String>()
    private var finishedKiosks = 0
    private var userNavigated = false

    private var itemWidth = 0

    // Menu lateral
    private val navViews = mutableMapOf<String?, View>()
    private var currentNav = HOME
    private val themeCache = mutableMapOf<String, List<HomeRow>>()
    private var pageAdapter: VideoAdapter? = null
    private var pageContinuation: String? = null
    private var pageLoading = false
    private var inSearch = false

    /** A Início está na tela (e não uma busca ou outra página do menu). */
    private fun onHomePage() = currentNav == HOME && !inSearch

    private lateinit var accountButton: Button
    private var accountName: String? = null
    private lateinit var searchTabs: View
    private lateinit var tabVideos: Button
    private lateinit var tabShorts: Button
    private val searchVideos = mutableListOf<StreamInfoItem>()
    private val searchShorts = mutableListOf<StreamInfoItem>()
    private var showingShorts = false

    // Shorts que vieram misturados nas fileiras da Home: viram uma fileira própria no fim
    private val homeShorts = mutableListOf<StreamInfoItem>()

    private var query: SearchQueryHandler? = null
    private var nextPage: Page? = null
    private var loading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        searchInput = findViewById(R.id.search_input)
        homeRows = findViewById(R.id.home_rows)
        results = findViewById(R.id.results)
        progress = findViewById(R.id.progress)
        status = findViewById(R.id.status)
        searchTabs = findViewById(R.id.search_tabs)
        tabVideos = findViewById(R.id.tab_videos)
        tabShorts = findViewById(R.id.tab_shorts)
        tabVideos.setOnClickListener { selectTab(showShorts = false) }
        tabShorts.setOnClickListener { selectTab(showShorts = true) }
        accountButton = findViewById(R.id.account_button)
        accountButton.setOnClickListener { onAccountClick() }
        if (YoutubeAuth.isLoggedIn(this)) accountButton.setText(R.string.account)

        // Cartões da Home com a mesma largura de uma coluna da grade de busca
        val density = resources.displayMetrics.density
        val contentWidth = resources.displayMetrics.widthPixels - (2 * 48 * density).toInt() -
            resources.getDimensionPixelSize(R.dimen.nav_width)
        itemWidth = contentWidth / COLUMNS - (2 * 10 * density).toInt()

        rowAdapter = RowAdapter(onClick = ::openVideo, itemWidthPx = itemWidth)
        buildNavMenu()
        homeRows.layoutManager = LinearLayoutManager(this)
        homeRows.adapter = rowAdapter

        results.layoutManager = GridLayoutManager(this, COLUMNS)
        results.adapter = searchAdapter

        searchInput.setOnEditorActionListener { _, actionId, event ->
            val isEnter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_SEARCH || isEnter) {
                search(searchInput.text.toString().trim())
                true
            } else {
                false
            }
        }

        // Atalhos para testes via adb: am start ... --es query "termo" | --es video "ID"
        intent.getStringExtra(EXTRA_VIDEO)?.let {
            startActivity(
                Intent(this, PlayerActivity::class.java)
                    .putExtra(PlayerActivity.EXTRA_URL, "https://www.youtube.com/watch?v=$it"),
            )
        }
        val initialQuery = intent.getStringExtra(EXTRA_QUERY)
        if (initialQuery != null) {
            searchInput.setText(initialQuery)
            search(initialQuery)
        } else {
            showStatus(null, showProgress = true)
        }
        loadHome()
    }

    private fun loadHome() {
        if (YoutubeAuth.isLoggedIn(this)) loadAccount()

        KIOSKS.forEach { (kioskId, titleRes) ->
            executor.execute {
                try {
                    val extractor = ServiceList.YouTube.kioskList.getExtractorById(kioskId, null)
                    extractor.fetchPage()
                    val (shorts, videos) = KioskInfo.getInfo(extractor).relatedItems
                        .filterIsInstance<StreamInfoItem>()
                        .partition { it.isShort }
                    runOnUiThread {
                        homeShorts += shorts
                        if (videos.isNotEmpty()) addHomeRow(kioskId, getString(titleRes), videos)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Falha ao carregar a fileira $kioskId", e)
                } finally {
                    runOnUiThread { onKioskFinished() }
                }
            }
        }
    }

    /** Menu lateral no estilo do YouTube: Início, seção "Você" (com login) e seção "Explorar". */
    private fun buildNavMenu() {
        val navMenu = findViewById<android.widget.LinearLayout>(R.id.nav_menu)
        val inflater = layoutInflater

        fun addHeader(titleRes: Int) {
            val header = TextView(this).apply {
                setText(titleRes)
                textSize = 15f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(0xFFFFFFFF.toInt())
                val d = resources.displayMetrics.density
                setPadding((15 * d).toInt(), (18 * d).toInt(), 0, (6 * d).toInt())
            }
            navMenu.addView(header)
        }

        fun addEntry(entry: NavEntry) {
            val view = inflater.inflate(R.layout.item_nav, navMenu, false)
            view.findViewById<android.widget.ImageView>(R.id.nav_icon).setImageResource(entry.iconRes)
            view.findViewById<TextView>(R.id.nav_text).setText(entry.titleRes)
            view.setOnClickListener { showNav(entry) }
            navMenu.addView(view)
            navViews[entry.id] = view
        }

        addEntry(HOME)
        if (YoutubeAuth.isLoggedIn(this)) {
            addHeader(R.string.nav_section_you)
            YOU.forEach(::addEntry)
        }
        addHeader(R.string.nav_section_explore)
        THEMES.forEach(::addEntry)
        markSelected(HOME)
    }

    private fun markSelected(entry: NavEntry) {
        navViews.forEach { (id, view) ->
            val selected = id == entry.id
            view.isSelected = selected
            view.findViewById<View>(R.id.nav_indicator).visibility = if (selected) View.VISIBLE else View.INVISIBLE
            view.findViewById<TextView>(R.id.nav_text).setTypeface(null, if (selected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        }
    }

    /** Troca o conteúdo da direita conforme o item do menu. */
    private fun showNav(entry: NavEntry) {
        inSearch = false
        currentNav = entry
        markSelected(entry)
        searchTabs.visibility = View.GONE
        when (entry.kind) {
            NavKind.HOME -> {
                results.visibility = View.GONE
                homeRows.visibility = View.VISIBLE
                homeRows.adapter = rowAdapter
                showStatus(if (rowAdapter.itemCount == 0) getString(R.string.search_tip) else null)
            }
            NavKind.SHELVES -> showShelves(entry.id!!)
            NavKind.GRID -> showGrid(entry.id!!)
        }
    }

    /** Página da conta (Histórico, Playlists...): grade com todos os itens, carregando mais ao rolar. */
    private fun showGrid(browseId: String) {
        homeRows.visibility = View.GONE
        results.visibility = View.VISIBLE
        val adapter = VideoAdapter(onClick = ::openVideo, onNearEnd = { loadMorePage(browseId) })
        pageAdapter = adapter
        pageContinuation = null
        pageLoading = true
        results.adapter = adapter
        showStatus(null, showProgress = true)

        executor.execute {
            val page = try {
                YoutubeTvApi.browse(this, browseId)
            } catch (e: Exception) {
                Log.w(TAG, "Falha ao carregar $browseId", e)
                null
            }
            runOnUiThread {
                if (currentNav.id != browseId || pageAdapter !== adapter || isDestroyed) return@runOnUiThread
                pageLoading = false
                pageContinuation = page?.continuation
                adapter.submit(page?.items.orEmpty())
                Log.i(TAG, "Página $browseId: ${page?.items?.size ?: 0} itens")
                showStatus(if (page?.items.isNullOrEmpty()) getString(R.string.theme_empty) else null)
            }
        }
    }

    private fun loadMorePage(browseId: String) {
        val continuation = pageContinuation ?: return
        val adapter = pageAdapter ?: return
        if (pageLoading) return
        pageLoading = true
        executor.execute {
            val page = try {
                YoutubeTvApi.browse(this, browseId, continuation)
            } catch (e: Exception) {
                Log.w(TAG, "Falha ao carregar mais de $browseId", e)
                null
            }
            runOnUiThread {
                if (pageAdapter !== adapter) return@runOnUiThread
                pageLoading = false
                pageContinuation = page?.continuation
                page?.items?.let(adapter::append)
            }
        }
    }

    /** Tema do Explorar: fileiras com título, como as páginas do YouTube. */
    private fun showShelves(themeId: String) {
        results.visibility = View.GONE
        homeRows.visibility = View.VISIBLE
        val adapter = RowAdapter(onClick = ::openVideo, itemWidthPx = itemWidth)
        homeRows.adapter = adapter
        themeCache[themeId]?.let {
            adapter.submit(it)
            showStatus(if (it.isEmpty()) getString(R.string.theme_empty) else null)
            return
        }

        showStatus(null, showProgress = true)
        executor.execute {
            val rows = try {
                YoutubeTvApi.browseShelves(this, themeId)
            } catch (e: Exception) {
                Log.w(TAG, "Falha ao carregar o tema $themeId", e)
                null
            }
            runOnUiThread {
                if (rows != null) themeCache[themeId] = rows
                if (currentNav.id != themeId || isDestroyed) return@runOnUiThread
                Log.i(TAG, "Tema $themeId: ${rows?.size ?: 0} fileiras")
                adapter.submit(rows.orEmpty())
                showStatus(if (rows.isNullOrEmpty()) getString(R.string.theme_empty) else null)
            }
        }
    }

    /** Com login: nome da conta no botão e fileiras de Inscrições e Histórico no topo. */
    private fun loadAccount() {
        executor.execute {
            try {
                val account = YoutubeTvApi.account(this)
                runOnUiThread {
                    accountName = account?.name
                    account?.name?.let { accountButton.text = it }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Falha ao carregar a conta", e)
            }
        }
        ACCOUNT_ROWS.forEach { (browseId, titleRes) ->
            executor.execute {
                try {
                    val (shorts, videos) = YoutubeTvApi.browse(this, browseId).items.partition { it.isShort }
                    Log.i(TAG, "$browseId: ${videos.size} vídeos, ${shorts.size} shorts")
                    runOnUiThread {
                        homeShorts += shorts
                        if (videos.isNotEmpty()) addHomeRow(browseId, getString(titleRes), videos)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Falha ao carregar $browseId", e)
                }
            }
        }
    }

    private fun onAccountClick() {
        if (!YoutubeAuth.isLoggedIn(this)) {
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(this, LoginActivity::class.java), REQUEST_LOGIN)
            return
        }
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.logout_confirm, accountName ?: ""))
            .setPositiveButton(R.string.logout) { _, _ ->
                YoutubeAuth.logout(this)
                recreate() // recarrega a Home sem as fileiras da conta
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_LOGIN && resultCode == RESULT_OK) recreate()
    }

    private fun onKioskFinished() {
        finishedKiosks++
        if (finishedKiosks == KIOSKS.size && homeShorts.isNotEmpty() && !isDestroyed) {
            rowAdapter.insert(rowAdapter.itemCount, HomeRow(getString(R.string.row_shorts), homeShorts.distinctBy { it.url }))
            if (onHomePage()) showStatus(null)
        }
        // Se nenhuma fileira carregou, troca o "carregando" pela dica de busca
        if (finishedKiosks == KIOSKS.size && rowAdapter.itemCount == 0 && onHomePage()) {
            showStatus(getString(R.string.search_tip))
        }
    }

    private fun addHomeRow(rowId: String, title: String, videos: List<StreamInfoItem>) {
        if (isDestroyed) return
        // Mantém a ordem definida em ROW_ORDER, independente de qual terminar primeiro
        val position = loadedKiosks.count { ROW_ORDER.indexOf(it) < ROW_ORDER.indexOf(rowId) }
        loadedKiosks += rowId
        rowAdapter.insert(position, HomeRow(title, videos))

        if (!onHomePage()) return
        showStatus(null)
        // Enquanto o usuário não navegou, mantém a Home no topo com foco no primeiro vídeo
        // (as fileiras chegam fora de ordem e podem ser inseridas acima da atual)
        if (!userNavigated) {
            homeRows.scrollToPosition(0)
            focusFirstVideo()
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) userNavigated = true
        return super.dispatchKeyEvent(event)
    }

    private fun firstHomeVideo(): View? {
        val firstRow = homeRows.layoutManager?.findViewByPosition(0) ?: return null
        val inner = firstRow.findViewById<RecyclerView>(R.id.row_list)
        return inner.layoutManager?.findViewByPosition(0)
    }

    /** Os cartões só existem depois do layout da fileira, então tenta de novo por alguns quadros. */
    private fun focusFirstVideo(attempt: Int = 0) {
        homeRows.post {
            val first = firstHomeVideo()
            if (first != null) {
                first.requestFocus()
            } else if (attempt < 10) {
                homeRows.postDelayed({ focusFirstVideo(attempt + 1) }, 50)
            }
        }
    }

    private fun search(text: String) {
        if (text.isEmpty() || loading) return
        loading = true
        showSearchMode()
        searchVideos.clear()
        searchShorts.clear()
        addSearchResults(emptyList()) // zera a contagem nas abas
        searchAdapter.submit(emptyList())
        showStatus(null, showProgress = true)

        executor.execute {
            try {
                val handler = ServiceList.YouTube.searchQHFactory
                    // O YouTube agrupa a maioria dos Shorts numa prateleira que o extractor ignora;
                    // os que vierem soltos nos resultados vão para a aba Shorts
                    .fromQuery(text, listOf(YoutubeSearchQueryHandlerFactory.VIDEOS), "")
                val info = SearchInfo.getInfo(ServiceList.YouTube, handler)
                val items = info.relatedItems.filterIsInstance<StreamInfoItem>()
                runOnUiThread {
                    query = handler
                    nextPage = info.nextPage
                    loading = false
                    addSearchResults(items)
                    // Abre na aba que tiver conteúdo (normalmente Vídeos)
                    selectTab(showShorts = searchVideos.isEmpty() && searchShorts.isNotEmpty())
                    if (items.isNotEmpty()) results.post { results.getChildAt(0)?.requestFocus() }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Falha na busca", e)
                runOnUiThread {
                    loading = false
                    showStatus(getString(R.string.error, e.message))
                }
            }
        }
    }

    private fun loadMore() {
        val handler = query ?: return
        val page = nextPage
        if (loading || !Page.isValid(page)) return
        loading = true

        executor.execute {
            try {
                val more = SearchInfo.getMoreItems(ServiceList.YouTube, handler, page)
                runOnUiThread {
                    nextPage = more.nextPage
                    loading = false
                    val added = addSearchResults(more.items.filterIsInstance<StreamInfoItem>())
                    searchAdapter.append(added)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Falha ao carregar mais resultados", e)
                runOnUiThread { loading = false }
            }
        }
    }

    /** Separa Shorts de vídeos; devolve os itens que entram na aba atual. */
    private fun addSearchResults(items: List<StreamInfoItem>): List<StreamInfoItem> {
        val (shorts, videos) = items.partition { it.isShort }
        searchVideos += videos
        searchShorts += shorts
        tabVideos.text = getString(R.string.tab_videos, searchVideos.size)
        tabShorts.text = getString(R.string.tab_shorts, searchShorts.size)
        // As abas só fazem sentido quando há Shorts para separar
        searchTabs.visibility = if (searchShorts.isNotEmpty() && results.visibility == View.VISIBLE) View.VISIBLE else View.GONE
        return if (showingShorts) shorts else videos
    }

    private fun selectTab(showShorts: Boolean) {
        showingShorts = showShorts
        tabVideos.isSelected = !showShorts
        tabShorts.isSelected = showShorts
        val list = if (showShorts) searchShorts else searchVideos
        searchAdapter.submit(list)
        results.scrollToPosition(0)
        showStatus(if (list.isEmpty()) getString(R.string.no_results) else null)
    }

    private fun showSearchMode() {
        homeRows.visibility = View.GONE
        results.visibility = View.VISIBLE
        results.adapter = searchAdapter
        inSearch = true
    }

    /** Sai da busca e volta para a página do menu que estava aberta. */
    private fun leaveSearch() {
        inSearch = false
        query = null
        searchInput.setText("")
        showNav(currentNav)
        if (currentNav == HOME) {
            homeRows.scrollToPosition(0)
            focusFirstVideo()
        } else {
            navViews[currentNav.id]?.requestFocus()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            inSearch -> leaveSearch()
            currentNav != HOME -> {
                showNav(HOME)
                navViews[null]?.requestFocus()
            }
            else -> super.onBackPressed()
        }
    }

    private fun openVideo(item: StreamInfoItem) {
        val extra = if (item.isPlaylist) PlayerActivity.EXTRA_PLAYLIST else PlayerActivity.EXTRA_URL
        startActivity(Intent(this, PlayerActivity::class.java).putExtra(extra, item.url))
    }

    private fun showStatus(message: String?, showProgress: Boolean = false) {
        progress.visibility = if (showProgress) View.VISIBLE else View.GONE
        status.visibility = if (message != null) View.VISIBLE else View.GONE
        status.text = message
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "BlockYou"
        private const val COLUMNS = 4
        private const val EXTRA_QUERY = "query"
        private const val EXTRA_VIDEO = "video"

        private const val REQUEST_LOGIN = 1

        /** Fileira da conta (só com login) no topo da Início; o resto fica na seção "Você" do menu. */
        private val ACCOUNT_ROWS = listOf(
            YoutubeTvApi.SUBSCRIPTIONS to R.string.row_subscriptions,
        )

        private val HOME = NavEntry(null, R.string.nav_home, R.drawable.ic_nav_home, NavKind.HOME)

        /** Seção "Você" (só com login): páginas da conta em grade. */
        private val YOU = listOf(
            NavEntry(YoutubeTvApi.SUBSCRIPTIONS, R.string.nav_subscriptions, R.drawable.ic_nav_subscriptions, NavKind.GRID),
            NavEntry(YoutubeTvApi.HISTORY, R.string.nav_history, R.drawable.ic_nav_history, NavKind.GRID),
            NavEntry(YoutubeTvApi.PLAYLISTS, R.string.nav_playlists, R.drawable.ic_nav_playlists, NavKind.GRID),
            NavEntry(YoutubeTvApi.WATCH_LATER, R.string.nav_watch_later, R.drawable.ic_nav_watch_later, NavKind.GRID),
            NavEntry(YoutubeTvApi.LIKED, R.string.nav_liked, R.drawable.ic_nav_liked, NavKind.GRID),
        )

        /** Seção "Explorar": páginas especiais do YouTube (IDs dos canais oficiais). */
        private val THEMES = listOf(
            NavEntry("UC-9-kyTW8ZkZNDHQJ6FgpwQ", R.string.nav_music, R.drawable.ic_nav_music, NavKind.SHELVES),
            NavEntry("UClgRkhTL3_hImCAmdLfDE4g", R.string.nav_movies, R.drawable.ic_nav_movies, NavKind.SHELVES),
            NavEntry("UC4R8DWoMoI7CAwX8_LjQHig", R.string.nav_live, R.drawable.ic_nav_live, NavKind.SHELVES),
            NavEntry("UCOpNcN46UbXVtpKMrmU4Abg", R.string.nav_gaming, R.drawable.ic_nav_gaming, NavKind.SHELVES),
            NavEntry("UCYfdidRxbB8Qhf0Nx7ioOYw", R.string.nav_news, R.drawable.ic_nav_news, NavKind.SHELVES),
            NavEntry("UCEgdi0XIXXZ-qJOFPf4JSKw", R.string.nav_sports, R.drawable.ic_nav_sports, NavKind.SHELVES),
            NavEntry("UCtFRv9O2AHqOZjjynzrv-xg", R.string.nav_learning, R.drawable.ic_nav_learning, NavKind.SHELVES),
        )

        /** Fileiras públicas da Home, na ordem em que aparecem. */
        private val KIOSKS = listOf(
            "trending_music" to R.string.row_music,
            "trending_gaming" to R.string.row_gaming,
            "trending_movies_and_shows" to R.string.row_movies,
            "live" to R.string.row_live,
            "trending_podcasts_episodes" to R.string.row_podcasts,
        )

        private val ROW_ORDER = ACCOUNT_ROWS.map { it.first } + KIOSKS.map { it.first }
    }
}
