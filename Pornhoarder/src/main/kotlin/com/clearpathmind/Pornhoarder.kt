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
    override var mainUrl = "https://pornhoarder.tv"
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

    private fun searchBody(query: String, latest: Boolean, page: Int): FormBody {
        return FormBody.Builder()
            .addEncoded("search", query)
            .addEncoded("sort", if (latest) "0" else "2")
            .addEncoded("date", "0")
            .addEncoded("servers[]", "40")
            .addEncoded("servers[]", "45")
            .addEncoded("servers[]", "12")
            .addEncoded("servers[]", "29")
            .addEncoded("servers[]", "25")
            .addEncoded("servers[]", "41")
            .addEncoded("servers[]", "46")
            .addEncoded("servers[]", "17")
            .addEncoded("servers[]", "44")
            .addEncoded("servers[]", "42")
            .addEncoded("servers[]", "43")
            .addEncoded("author", "0")
            .addEncoded("page", page.toString())
            .build()
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = if (request.data == "Latest" || request.data == "Popular") {
            app.post(
                ajaxUrl,
                requestBody = searchBody("", request.data == "Latest", page),
                interceptor = cfKiller,
                headers = requestHeaders()
            ).document
        } else {
            app.get(
                "$mainUrl${request.data}?page=$page",
                interceptor = cfKiller,
                headers = requestHeaders()
            ).document
        }
        checkNotBlocked(document.html())
        val home = document.select(".video article").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(
            HomePageList(request.name, home, isHorizontalImages = true),
            hasNext = home.isNotEmpty()
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.select(".video-content h1").text()
            .replace("| PornHoarder.tv", "").trim()
            .ifBlank { return null }
        val href = fixUrlNull(mainUrl + this.select(".video-link").attr("href"))
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
        val document = app.post(
            ajaxUrl,
            requestBody = searchBody(query, true, page),
            interceptor = cfKiller,
            headers = requestHeaders()
        ).document
        checkNotBlocked(document.html())
        val results = document.select(".video article").mapNotNull { it.toSearchResult() }
        return newSearchResponseList(results, results.isNotEmpty())
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, interceptor = cfKiller, headers = requestHeaders()).document
        checkNotBlocked(document.html())
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
        val doc = app.get(data, interceptor = cfKiller, headers = requestHeaders()).document
        checkNotBlocked(doc.html())
        val serverPages = mutableListOf(data)
        doc.select(".video-detail-servers li a").forEach { item ->
            fixUrlNull(mainUrl + item.attr("href"))?.let { serverPages.add(it) }
        }
        var found = false
        serverPages.distinct().forEach { pageUrl ->
            val pageDoc = if (pageUrl == data) doc else
                app.get(pageUrl, interceptor = cfKiller, headers = requestHeaders()).document
            val iframeSrc = fixUrlNull(pageDoc.selectFirst(".video-player iframe")?.attr("src"))
                ?: return@forEach
            val playBody = FormBody.Builder().addEncoded("play", "").build()
            val innerDoc = app.post(iframeSrc, requestBody = playBody, headers = requestHeaders()).document
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
            "attention required"
        )
    }
}
