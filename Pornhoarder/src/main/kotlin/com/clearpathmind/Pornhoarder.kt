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
    override var mainUrl = "https://ww3.pornhoarder.org"
    override var name = "PornHoarder"
    override val hasMainPage = true
    override var lang = "en"
    override val hasQuickSearch = false
    override val hasDownloadSupport = true
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(TvType.NSFW)
    override val vpnStatus = VPNStatus.MightBeNeeded

    private val cfKiller get() = CloudflareKiller()
    private val ajaxUrl get() = "$mainUrl/ajax_search.php"

    /** Previous primary domain (currently Cloudflare edge-broken, Error 1034). */
    private val fallbackUrl = "https://pornhoarder.tv"

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
        val attempts = listOf(url, url.replace(mainUrl, fallbackUrl)).distinct()
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

    override val mainPage = mainPageOf(
        "Latest" to "Latest Videos",
        "Popular" to "Popular Videos",
        "/trending-videos/" to "Trending Videos",
        "/random-videos/" to "Random Videos"
    )

    private fun requestHeaders() = mapOf(
        "Referer" to "$mainUrl/",
        "User-Agent" to USER_AGENT
    ) + CloudflareSolver.storedCookies().let { cookies ->
        if (cookies.isNotBlank()) mapOf("Cookie" to cookies) else emptyMap()
    }

    /**
     * Discovers the site's live listing/search form (action URL + fields) so
     * POST params track the site even when they change. Falls back to the
     * legacy hardcoded body when discovery fails. Cached per session.
     */
    private var formCache: DiscoveredForm? = null
    private data class DiscoveredForm(val action: String, val fields: Map<String, List<String>>)

    private suspend fun discoverForm(): DiscoveredForm? {
        formCache?.let { return it }
        return try {
            val doc = app.get(mainUrl, interceptor = cfKiller, headers = requestHeaders()).document
            val form = doc.select("form").firstOrNull { f ->
                f.selectFirst("input[name=search], input[name=s], input[name=q], input[name=query]") != null ||
                    f.attr("action").contains("ajax", true) ||
                    f.attr("action").contains("search", true)
            } ?: return null
            val actionAttr = form.attr("action")
            val action = fixUrlNull(if (actionAttr.isBlank()) ajaxUrl else actionAttr) ?: ajaxUrl
            val fields = mutableMapOf<String, MutableList<String>>()
            form.select("input[name], select[name], textarea[name]").forEach { el ->
                val fname = el.attr("name")
                if (fname.isBlank()) return@forEach
                when (el.tagName().lowercase()) {
                    "input" -> {
                        val t = el.attr("type").lowercase()
                        if (t == "checkbox" || t == "radio") {
                            if (el.hasAttr("checked")) {
                                fields.getOrPut(fname) { mutableListOf() }
                                    .add(el.attr("value").ifBlank { "1" })
                            }
                        } else if (t != "submit" && t != "button" && t != "image" && t != "file") {
                            fields.getOrPut(fname) { mutableListOf() }.add(el.attr("value"))
                        }
                    }
                    "select" -> {
                        val selected = el.select("option[selected]")
                        val opts = if (selected.isNotEmpty()) selected else el.select("option").take(1)
                        opts.forEach { o ->
                            fields.getOrPut(fname) { mutableListOf() }.add(o.attr("value"))
                        }
                    }
                    else -> fields.getOrPut(fname) { mutableListOf() }.add(el.text())
                }
            }
            DiscoveredForm(action, fields).also { formCache = it }
        } catch (_: Exception) {
            null
        }
    }

    private fun buildListingBody(
        query: String,
        latest: Boolean,
        page: Int,
        form: DiscoveredForm?
    ): Pair<String, FormBody> {
        val action = form?.action ?: ajaxUrl
        val b = FormBody.Builder()
        var searchSet = false
        var sortSet = false
        var pageSet = false
        var hasServers = false
        form?.fields?.forEach { (fname, vals) ->
            when {
                fname.equals("search", true) || fname.equals("s", true) ||
                    fname.equals("q", true) || fname.equals("query", true) -> {
                    b.add(fname, query)
                    searchSet = true
                }
                fname.equals("sort", true) || fname.equals("orderby", true) ||
                    fname.equals("order", true) -> {
                    b.add(fname, if (latest) "0" else "2")
                    sortSet = true
                }
                fname.equals("page", true) || fname.equals("paged", true) -> {
                    b.add(fname, page.toString())
                    pageSet = true
                }
                fname.equals("servers[]", true) || fname.equals("servers", true) -> {
                    vals.forEach { b.add(fname, it) }
                    hasServers = true
                }
                else -> vals.forEach { b.add(fname, it) }
            }
        }
        if (!searchSet) b.add("search", query)
        if (!sortSet) b.add("sort", if (latest) "0" else "2")
        if (!pageSet) b.add("page", page.toString())
        if (form == null && !hasServers) {
            // NOTE: use add() (not addEncoded) so values are URL-encoded.
            listOf("40", "45", "12", "29", "25", "41", "46", "17", "44", "42", "43")
                .forEach { b.add("servers[]", it) }
            b.add("date", "0")
            b.add("author", "0")
        }
        return action to b.build()
    }

    private fun selectArticles(doc: org.jsoup.nodes.Document): List<SearchResponse> =
        doc.select(".video article").mapNotNull { it.toSearchResult() }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val home = if (request.data == "Latest" || request.data == "Popular") {
            val form = discoverForm()
            val (action, body) = buildListingBody("", request.data == "Latest", page, form)
            selectArticles(postDoc(action, body))
        } else {
            selectArticles(getDoc("$mainUrl${request.data}?page=$page"))
        }
        return newHomePageResponse(
            HomePageList(request.name, home, isHorizontalImages = true),
            hasNext = home.isNotEmpty()
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.select(".video-content h1").text()
            .replace("| PornHoarder.tv", "").trim()
            .ifBlank { return null }
        val rawHref = this.select(".video-link").attr("href").ifBlank { return null }
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
        val form = discoverForm()
        val (action, body) = buildListingBody(q, true, page, form)
        var results = selectArticles(postDoc(action, body))
        // Fallback: plain GET search URLs when the ajax endpoint yields nothing.
        if (results.isEmpty() && q.isNotBlank()) {
            for (u in listOf("$mainUrl/search/$q/", "$mainUrl/?s=$q")) {
                try {
                    val r = selectArticles(getDoc(u))
                    if (r.isNotEmpty()) {
                        results = r
                        break
                    }
                } catch (_: Exception) {
                }
            }
        }
        return newSearchResponseList(results, results.isNotEmpty())
    }

    override suspend fun load(url: String): LoadResponse {
        val document = getDoc(url)
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
