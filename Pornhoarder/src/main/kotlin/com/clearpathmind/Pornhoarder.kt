package com.clearpathmind

import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.VPNStatus
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import okhttp3.FormBody
import org.jsoup.nodes.Element

class Pornhoarder : MainAPI() {
    override var mainUrl = "https://ww8.pornhoarder.org"
    override var name = "PornHoarder"
    override val hasMainPage = true
    override var lang = "en"
    override val hasQuickSearch = false
    override val hasDownloadSupport = true
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(TvType.NSFW)
    override val vpnStatus = VPNStatus.MightBeNeeded

    private val cfKiller get() = CloudflareKiller()

    /**
     * Mirror rotation: subdomains rotate (ww3 → ww8 …) and the bare domain is
     * Cloudflare edge-broken (Error 1034). Every request cycles the hosts.
     */
    private val mirrorHosts = listOf(
        "https://ww8.pornhoarder.org",
        "https://ww3.pornhoarder.org",
        "https://pornhoarder.tv"
    )

    private fun withHost(url: String, host: String): String {
        mirrorHosts.forEach { h ->
            if (url.startsWith(h)) return host + url.removePrefix(h)
        }
        return if (url.startsWith("http")) url else host + url
    }

    /** GET with mirror fallback: primary → fallback on edge-dead pages. */
    private suspend fun getDoc(url: String) = fetchWithFallback(url, isPost = false, body = null)

    /** POST with mirror fallback. */
    private suspend fun postDoc(url: String, body: FormBody) =
        fetchWithFallback(url, isPost = true, body = body)

    private suspend fun fetchWithFallback(
        url: String,
        isPost: Boolean,
        body: FormBody?
    ): org.jsoup.nodes.Document {
        val attempts = mirrorHosts.map { withHost(url, it) }.distinct()
        var lastEdgeError: ErrorLoadingException? = null
        for (attempt in attempts) {
            val html = if (isPost && body != null) {
                app.post(attempt, requestBody = body, interceptor = cfKiller, headers = requestHeaders()).text
            } else {
                app.get(attempt, interceptor = cfKiller, headers = requestHeaders()).text
            }
            if (EDGE_MARKERS.any { html.contains(it, ignoreCase = true) }) {
                lastEdgeError = ErrorLoadingException("Site unreachable (Cloudflare edge) — try again later")
                continue
            }
            checkNotBlocked(html)
            return org.jsoup.Jsoup.parse(html)
        }
        throw lastEdgeError ?: ErrorLoadingException("Site unreachable (Cloudflare edge) — try again later")
    }

    // Verified listing pattern (user-reported):
    // Latest  → /search/?search=&sort=0
    // Popular → /search/?search=&sort=2
    override val mainPage = mainPageOf(
        "$mainUrl/search/?search=&sort=0" to "Latest Videos",
        "$mainUrl/search/?search=&sort=2" to "Popular Videos",
        "$mainUrl/trending-videos/" to "Trending Videos",
        "$mainUrl/random-videos/" to "Random Videos",
        "$mainUrl/categories/" to "Categories",
        "$mainUrl/pornstars/" to "Pornstars",
        "$mainUrl/studios/" to "Studios"
    )

    private fun requestHeaders() = mapOf(
        "Referer" to "$mainUrl/",
        "User-Agent" to USER_AGENT
    ) + CloudflareSolver.storedCookies().let { cookies ->
        if (cookies.isNotBlank()) mapOf("Cookie" to cookies) else emptyMap()
    }

    /** Verified site pattern: GET /search/?search=<q>&sort=<0|2>[&page=N]. */
    private fun listingUrl(query: String, sort: String, page: Int): String {
        val q = java.net.URLEncoder.encode(query, "UTF-8")
        return "$mainUrl/search/?search=$q&sort=$sort" + if (page > 1) "&page=$page" else ""
    }

    /** Tries several card selectors: listing templates differ per page type. */
    private val articleSelectors = listOf(
        ".video article",
        ".videos article",
        ".video-list article",
        ".video-item",
        ".thumb-block",
        ".video-block",
        "article"
    )

    private fun selectArticles(doc: org.jsoup.nodes.Document): List<SearchResponse> {
        for (sel in articleSelectors) {
            val r = doc.select(sel).mapNotNull { it.toSearchResult() }
            if (r.isNotEmpty()) return r
        }
        return emptyList()
    }

    /** Legacy ajax fallback when the GET listing parses to nothing. */
    private fun legacyBody(query: String, latest: Boolean, page: Int): FormBody {
        val b = FormBody.Builder()
            .add("search", query)
            .add("sort", if (latest) "0" else "2")
            .add("date", "0")
            .add("author", "0")
            .add("page", page.toString())
        listOf("40", "45", "12", "29", "25", "41", "46", "17", "44", "42", "43")
            .forEach { b.add("servers[]", it) }
        return b.build()
    }

    /** Index pages (categories / pornstars / studios) with path fallbacks. */
    private val indexPaths = mapOf(
        "Categories" to listOf("/categories/"),
        "Pornstars" to listOf("/pornstars/", "/pornstar/", "/models/"),
        "Studios" to listOf("/studios/", "/channels/")
    )

    private suspend fun fetchIndex(name: String): List<SearchResponse> {
        for (p in indexPaths[name].orEmpty()) {
            try {
                val r = selectIndex(getDoc(withHost("$mainUrl$p", mainUrl)))
                if (r.isNotEmpty()) return r
            } catch (_: Exception) {
            }
        }
        return emptyList()
    }

    /** Parses index grids: anchors wrapping a thumbnail image + short label. */
    private fun selectIndex(doc: org.jsoup.nodes.Document): List<SearchResponse> {
        val skipPaths = setOf("/", "/login", "/login/", "/signup", "/sign-up", "/settings",
            "/settings/", "/contact", "/contact/", "/abuse", "/about")
        return doc.select("a[href]").mapNotNull { a ->
            val raw = a.attr("href").trim()
            if (raw.isBlank()) return@mapNotNull null
            val abs = fixUrlNull(if (raw.startsWith("http")) raw else mainUrl + raw)
                ?: return@mapNotNull null
            if (!abs.startsWith(mainUrl)) return@mapNotNull null
            if (abs.removePrefix(mainUrl) in skipPaths) return@mapNotNull null
            val img = a.selectFirst("img") ?: return@mapNotNull null
            val title = img.attr("alt").trim()
                .ifBlank { a.selectFirst("span, strong, b, h2, h3")?.text()?.trim().orEmpty() }
                .ifBlank { a.text().trim() }
                .ifBlank { return@mapNotNull null }
            if (title.length > 60) return@mapNotNull null
            val poster = fixUrlNull(
                img.attr("data-src").ifBlank { null } ?: img.attr("src").ifBlank { null }
            ) ?: return@mapNotNull null
            newMovieSearchResponse(title, abs, TvType.NSFW) {
                this.posterUrl = poster
            }
        }.distinctBy { it.url }.take(200)
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (request.name in indexPaths) {
            val home = if (page > 1) emptyList() else fetchIndex(request.name)
            return newHomePageResponse(
                HomePageList(request.name, home, isHorizontalImages = true),
                hasNext = false
            )
        }
        val base = withHost(request.data, mainUrl)
        val sep = if (base.contains("?")) "&" else "?"
        val url = if (page > 1) "$base${sep}page=$page" else base
        var home = selectArticles(getDoc(url))
        if (home.isEmpty() && (request.name == "Latest Videos" || request.name == "Popular Videos")) {
            // Fallback: legacy ajax endpoint (same feed, may duplicate sort order).
            try {
                home = selectArticles(postDoc("$mainUrl/ajax_search.php",
                    legacyBody("", request.name == "Latest Videos", page)))
            } catch (_: Exception) {
            }
        }
        return newHomePageResponse(
            HomePageList(request.name, home, isHorizontalImages = true),
            hasNext = home.isNotEmpty()
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.select(".video-content h1").text()
            .replace("| PornHoarder.tv", "").trim()
            .ifBlank {
                this.selectFirst("a[title]")?.attr("title")?.trim().orEmpty()
            }
            .ifBlank {
                this.selectFirst("img[alt]")?.attr("alt")?.trim().orEmpty()
            }
            .ifBlank { return null }
        val rawHref = (
            this.select(".video-link").attr("href").ifBlank { null }
                ?: this.select("a.video-link").attr("href").ifBlank { null }
                ?: this.select(".video-image a").attr("href").ifBlank { null }
                ?: this.select("a[href^=/video]").attr("href").ifBlank { null }
                ?: return null
            )
        val href = fixUrlNull(if (rawHref.startsWith("http")) rawHref else mainUrl + rawHref)
            ?: return null
        val posterUrl = fixUrlNull(
            this.selectFirst(".video-image.primary.b-lazy")?.attr("data-src")
                ?: this.selectFirst(".video-image img")?.attr("data-src")
                ?: this.selectFirst("img")?.attr("src")
        )
        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val q = query.trim()
        if (q.isBlank()) return newSearchResponseList(emptyList(), false)
        val enc = java.net.URLEncoder.encode(q, "UTF-8")
        val pageSuffix = if (page > 1) "&page=$page" else ""
        val candidates = listOf(
            "$mainUrl/search/?search=$enc&sort=0$pageSuffix",
            "$mainUrl/search/?search=$enc$pageSuffix",
            "$mainUrl/search/?search=$enc&sort=2$pageSuffix",
            "$mainUrl/search/$enc/"
        )
        // Reference feed: reject candidate sets identical to unfiltered Latest.
        val latestUrls = try {
            selectArticles(getDoc(listingUrl("", "0", 1))).map { it.url }.toSet()
        } catch (_: Exception) {
            emptySet()
        }
        for (u in candidates) {
            try {
                val r = selectArticles(getDoc(u))
                if (r.isEmpty()) continue
                val urls = r.map { it.url }.toSet()
                if (latestUrls.isNotEmpty() && urls == latestUrls) continue
                return newSearchResponseList(r, true)
            } catch (_: Exception) {
            }
        }
        // Last resort: legacy ajax (may be unfiltered, but non-empty).
        try {
            val r = selectArticles(postDoc("$mainUrl/ajax_search.php", legacyBody(q, true, page)))
            if (r.isNotEmpty()) return newSearchResponseList(r, true)
        } catch (_: Exception) {
        }
        return newSearchResponseList(emptyList(), false)
    }

    override suspend fun load(url: String): LoadResponse {
        val document = getDoc(url)
        if (document.selectFirst(".video-player") == null) {
            // Index page (category / pornstar / studio): expose its videos.
            val title = document.selectFirst("h1")?.text()?.trim()
                ?.ifBlank { null }
                ?: document.selectFirst("meta[property=og:title]")
                    ?.attr("content")?.trim()?.replace("| PornHoarder.tv", "")
                ?: "PornHoarder"
            val poster = fixUrlNull(document.selectFirst("[property='og:image']")?.attr("content"))
            val recs = selectArticles(document)
            return newMovieLoadResponse(title, url, TvType.NSFW, url) {
                this.posterUrl = poster
                this.plot = if (recs.isEmpty()) null else "Browse — ${recs.size} videos"
                this.recommendations = recs.ifEmpty { null }
            }
        }
        val title = document.selectFirst("meta[property=og:title]")
            ?.attr("content")?.trim()?.replace("| PornHoarder.tv", "")
            ?.ifBlank { null } ?: "PornHoarder video"
        val poster = fixUrlNull(document.selectFirst("[property='og:image']")?.attr("content"))
        val description = document.selectFirst("meta[property=og:description]")
            ?.attr("content")?.trim()
        val tags = document.select(".video-tags a, .tags a").map { it.text().trim() }
            .filter { it.isNotBlank() }
        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags.ifEmpty { null }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = getDoc(data)
        val serverPages = mutableListOf(data)
        doc.select(".video-detail-servers li a").forEach { item ->
            fixUrlNull(mainUrl + item.attr("href"))?.let { serverPages.add(it) }
        }
        var found = false
        serverPages.distinct().forEach { pageUrl ->
            val pageDoc = if (pageUrl == data) doc else
                getDoc(pageUrl)
            val iframeSrc = fixUrlNull(pageDoc.selectFirst(".video-player iframe")?.attr("src"))
                ?: return@forEach
            val playBody = FormBody.Builder().addEncoded("play", "").build()
            val innerDoc = postDoc(iframeSrc, playBody)
            val hosterUrl = fixUrlNull(innerDoc.selectFirst("iframe")?.attr("src"))
                ?: return@forEach
            if (loadExtractor(hosterUrl, data, subtitleCallback, callback)) found = true
        }
        return found
    }

    private fun checkNotBlocked(html: String) {
        if (BLOCKED_MARKERS.any { html.contains(it, ignoreCase = true) }) {
            throw ErrorLoadingException("Cloudflare challenge — solve it in provider settings")
        }
    }

    companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        private val BLOCKED_MARKERS = listOf(
            "checking your browser",
            "just a moment",
            "cf-challenge",
            "cf_clearance",
            "attention required",
            "verify you are human"
        )
        private val EDGE_MARKERS = listOf(
            "edge ip restricted",
            "error 1034"
        )
    }
}
