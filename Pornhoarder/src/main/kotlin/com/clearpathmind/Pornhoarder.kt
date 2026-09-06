package com.clearpathmind

import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
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
    private val mirrorHosts get() = MIRROR_HOSTS

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
        "$mainUrl/random-videos/" to "Random Videos"
    )

    private fun requestHeaders() = mapOf(
        "Referer" to "$mainUrl/",
        "User-Agent" to USER_AGENT
    ) + CloudflareSolver.storedCookies().let { cookies ->
        if (cookies.isNotBlank()) mapOf("Cookie" to cookies) else emptyMap()
    }

    /**
     * Forces orientation to Straight (value 0) once per session via the
     * site's own settings form (POST /settings/). Silent on failure —
     * never breaks listings. Response cookies are merged into the
     * persisted store (explicit Cookie headers bypass NiceHttp's jar).
     */
    private var orientationEnsured = false

    private suspend fun ensureStraightOrientation() {
        if (orientationEnsured) return
        orientationEnsured = true
        try {
            val body = FormBody.Builder()
                .add("sexual-orientation", "0")
                .add("theme", "1")
                .add("size", "0")
                .add("web_size", "1")
                .build()
            val res = app.post(
                withHost("$mainUrl/settings/", mainUrl),
                requestBody = body,
                interceptor = cfKiller,
                headers = requestHeaders()
            )
            // Collect Set-Cookie from every hop via the wrapped okhttp
            // response: the settings POST answers with a 302 redirect and
            // the preference cookie is set on the intermediate response.
            val allSetCookies = mutableListOf<String>()
            var prior = res.okhttpResponse.priorResponse
            while (prior != null) {
                allSetCookies.addAll(prior.headers.values("Set-Cookie"))
                prior = prior.priorResponse
            }
            allSetCookies.addAll(res.headers.values("Set-Cookie"))
            CloudflareSolver.mergeCookies(allSetCookies)
        } catch (_: Exception) {
        }
    }

    private var baseCookiesEnsured = false

    /** Merges the user-approved baked cookies (Straight config) into the
     * persisted store so every request carries them from the first call. */
    private fun ensureBaseCookies() {
        if (baseCookiesEnsured) return
        baseCookiesEnsured = true
        try {
            CloudflareSolver.mergeCookies(
                BAKED_COOKIES.map { (k, v) -> "$k=$v; path=/" }
            )
        } catch (_: Exception) {
        }
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

    private fun selectArticles(root: org.jsoup.nodes.Element): List<SearchResponse> {
        for (sel in articleSelectors) {
            val r = root.select(sel).mapNotNull { it.toSearchResult() }
            if (r.isNotEmpty()) return r
        }
        return emptyList()
    }

    /** "Similar Videos" section → recommendations; falls back to page cards. */
    private fun similarVideos(
        doc: org.jsoup.nodes.Document,
        currentUrl: String
    ): List<SearchResponse>? {
        // Pinned exact path first (verified page structure).
        var recs: List<SearchResponse> = doc
            .select(".video-detail-right .video-list .video article, .video-list .video article")
            .mapNotNull { it.toSearchResult() }
        if (recs.isEmpty()) {
            val heading = doc.select("h1, h2, h3, h4, .title, .heading, .page-header")
                .firstOrNull { it.text().contains("similar videos", ignoreCase = true) }
            if (heading != null) {
                var section: org.jsoup.nodes.Element? = heading.parent()
                var depth = 0
                while (section != null && depth < 4) {
                    recs = selectArticles(section)
                    if (recs.isNotEmpty()) break
                    section = section.parent()
                    depth++
                }
            }
        }
        if (recs.isEmpty()) recs = selectArticles(doc)
        return recs.filter { it.url != currentUrl }.take(24).ifEmpty { null }
    }

    /** Duration in minutes. Priority: exact info item
     * (.video-info .item[title*=duration]) → og:video:duration meta →
     * JSON-LD VideoObject "duration" → info-block time → page times.
     * Plain integers in meta/JSON-LD are seconds (e.g. 2036 → 33). */
    private fun extractDurationMinutes(doc: org.jsoup.nodes.Document): Int? {
        doc.selectFirst(".video-info .item[title*=duration], .video-details .item[title*=duration]")
            ?.text()?.let { parseDurationMinutes(it)?.let { m -> return m } }
        doc.select("script[type=application/ld+json]").forEach { s ->
            Regex(""""duration"\s*:\s*"([^"]+)"""").findAll(s.data()).forEach { m ->
                val raw = m.groupValues[1]
                parseSecondsPlain(raw)?.let { v -> return v }
                parseDurationMinutes(raw)?.let { v -> return v }
            }
        }
        val scope = doc.selectFirst(".video-info, .video-details, .video-meta") ?: doc
        for (sel in listOf(".duration", ".video-duration", "[class*=duration]", ".video-time", ".time")) {
            scope.select(sel).forEach { el ->
                parseDurationMinutes(el.text())?.let { m -> return m }
            }
        }
        // Last resorts: any duration-like string in JSON-LD, then the first
        // timestamp in the main content area (nav excluded structurally).
        doc.select("script[type=application/ld+json]").forEach { s ->
            parseDurationMinutes(s.data())?.let { m -> return m }
        }
        val main = doc.selectFirst("main") ?: doc
        parseDurationMinutes(main.text())?.let { m -> return m }
        return null
    }

    /** Plain second counts (10s … 12h); only for meta/JSON-LD values. */
    private fun parseSecondsPlain(text: String): Int? {
        val secs = text.trim().toLongOrNull() ?: return null
        if (secs < 10 || secs > 43200) return null
        return (secs / 60).toInt().coerceAtLeast(1)
    }

    private fun parseDurationMinutes(text: String): Int? {
        // ISO8601 with optional P (site emits "T33M56S" without it).
        Regex("""\bP?T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?""").find(text)?.let { m ->
            if (m.value != "PT" && m.value != "T") {
                val h = m.groupValues[1].toIntOrNull() ?: 0
                val mi = m.groupValues[2].toIntOrNull() ?: 0
                val s = m.groupValues[3].toIntOrNull() ?: 0
                return (h * 60 + mi + s / 60).coerceAtLeast(1)
            }
        }
        Regex("""\b(\d{1,3}):(\d{2})(?::(\d{2}))?\b""").find(text)?.let { m ->
            val a = m.groupValues[1].toIntOrNull() ?: return null
            val b = m.groupValues[2].toIntOrNull() ?: 0
            val c = m.groupValues[3].toIntOrNull()
            return if (c != null) (a * 60 + b).coerceAtLeast(1) else if (a == 0) 1 else a
        }
        return null
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

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        ensureBaseCookies()
        ensureStraightOrientation()
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
        // Thumbnails live in data-src when lazy, or inline background-image
        // style once loaded — cover both.
        val styleUrl = this.select("[style*=url]").firstNotNullOfOrNull { el ->
            Regex("""url\(['"]?(.*?)['"]?\)""")
                .find(el.attr("style"))?.groupValues?.get(1)
                ?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
        }
        val posterUrl = fixUrlNull(
            this.selectFirst(".video-image.primary.b-lazy")?.attr("data-src")?.ifBlank { null }
                ?: this.selectFirst(".video-image img")?.attr("data-src")?.ifBlank { null }
                ?: this.selectFirst("img")?.attr("src")?.ifBlank { null }
                ?: styleUrl
        )
        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val q = query.trim()
        if (q.isBlank()) return newSearchResponseList(emptyList(), false)
        ensureBaseCookies()
        ensureStraightOrientation()
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
        val title = document.selectFirst("meta[property=og:title]")
            ?.attr("content")?.trim()?.replace("| PornHoarder.tv", "")
            ?.ifBlank { null } ?: "PornHoarder video"
        val poster = fixUrlNull(document.selectFirst("[property='og:image']")?.attr("content"))
        val description = document.selectFirst("meta[property=og:description]")
            ?.attr("content")?.trim()
            ?.ifBlank { null }
            ?: document.selectFirst(".video-detail-description p")?.text()?.trim()
                ?.ifBlank { null }
        val scope = document.selectFirst("main") ?: document
        val tags = (
            document.select(".video-tags a, .tags a, a[rel=tag]") +
                scope.select("a[href*=tag]")
            )
            .map { it.text().trim() }
            .filter { it.isNotBlank() && it.length <= 40 && !it.contains(" ") }
            .distinct()
        // Pornstars list doubles as cast info on this template.
        val actors = document.select("ul.video-detail-list a.video-pornstar").mapNotNull { a ->
            val name = a.selectFirst("h4, .video-pornstar-title")?.text()?.trim()
                .ifBlank { null } ?: return@mapNotNull null
            val image = fixUrlNull(
                a.selectFirst(".user-avatar-image")?.attr("data-src")?.ifBlank { null }
                    ?: a.selectFirst("img")?.attr("src")?.ifBlank { null }
            )
            Actor(name, image)
        }
        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags.ifEmpty { null }
            this.duration = extractDurationMinutes(document)
            this.recommendations = similarVideos(document, url)
            addActors(actors)
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
        val MIRROR_HOSTS = listOf(
            "https://ww8.pornhoarder.org",
            "https://ww3.pornhoarder.org",
            "https://pornhoarder.tv"
        )
        /** User-approved Straight config baked into every session. */
        val BAKED_COOKIES = mapOf(
            "pornhoarder_settings" to "0---0---1---1",
            "pornhoarder_a" to "1"
        )
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
