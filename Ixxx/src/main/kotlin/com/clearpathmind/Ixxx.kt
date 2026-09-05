package com.clearpathmind

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLDecoder

class Ixxx : MainAPI() {
    override var mainUrl = "https://www.ixxx.com"
    override var name = "Ixxx"
    override val hasMainPage = true
    override var lang = "en"
    override val hasQuickSearch = false
    override val hasDownloadSupport = true
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(TvType.NSFW)
    override val vpnStatus = VPNStatus.MightBeNeeded

    override val mainPage = mainPageOf(
        "$mainUrl/popular" to "Popular",
        "$mainUrl/new" to "New",
        "$mainUrl/rating" to "Top Rated",
        "$mainUrl/c/big-tits" to "Big Tits",
        "$mainUrl/c/milf" to "MILF",
        "$mainUrl/c/latina" to "Latina",
        "$mainUrl/c/pov-point-of-view" to "POV",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data}?page=$page"
        val document = app.get(url).document
        val home = document.select("div.cards-container div.card").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home,
                isHorizontalImages = true
            ),
            hasNext = home.isNotEmpty()
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleEl = this.selectFirst("a.item-title") ?: return null
        val title = titleEl.attr("title").ifBlank { titleEl.text() }.trim()
        if (title.isBlank()) return null
        val outHref = titleEl.attr("href").ifBlank {
            this.selectFirst("a.item-link")?.attr("href")
        } ?: return null
        val img = this.selectFirst("img.item-image")
        val poster = fixUrlNull(
            img?.attr("src")?.takeIf { it.isNotBlank() }
                ?: img?.attr("data-src")
        )
        val source = this.selectFirst("a.item-source")?.text()?.trim()
        // Thread metadata through: /out/ links redirect externally, so load() can't re-scrape it.
        val data = listOf(fixUrl(outHref), title, poster.orEmpty(), source.orEmpty())
            .joinToString(SEP)
        return newMovieSearchResponse(title, data, TvType.NSFW) {
            this.posterUrl = poster
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val q = query.trim().replace(" ", "-")
        val base = "$mainUrl/search/$q"
        val url = if (page <= 1) base else "$base?page=$page"
        val document = app.get(url).document
        val results = document.select("div.cards-container div.card").mapNotNull { it.toSearchResult() }
        return newSearchResponseList(results, results.isNotEmpty())
    }

    override suspend fun load(url: String): LoadResponse {
        val (outUrl, title, poster, source) = url.split(SEP).let {
            TripleFour(
                it.getOrNull(0).orEmpty(),
                it.getOrNull(1)?.takeIf { s -> s.isNotBlank() } ?: "Ixxx video",
                it.getOrNull(2)?.takeIf { s -> s.isNotBlank() },
                it.getOrNull(3)?.takeIf { s -> s.isNotBlank() }
            )
        }
        return newMovieLoadResponse(title, url, TvType.NSFW, outUrl.ifBlank { url }) {
            this.posterUrl = poster?.let { fixUrlNull(it) }
            this.plot = source?.let { "Source: $it" }
            this.tags = listOfNotNull(source)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val outUrl = fixUrl(data.split(SEP).first().ifBlank { data })
        val partnerUrl = resolveOutUrl(outUrl) ?: return false
        // Built-in extractors cover major partners (XHamster, Eporner, ...).
        if (loadExtractor(partnerUrl, mainUrl, subtitleCallback, callback)) return true
        // Fallback: hand the partner page URL to the player.
        callback(
            newExtractorLink(
                source = name,
                name = "$name:${domainOf(partnerUrl)}",
                url = partnerUrl,
                type = ExtractorLinkType.VIDEO
            ) {
                this.referer = "$mainUrl/"
                this.quality = Qualities.Unknown.value
            }
        )
        return true
    }

    private suspend fun resolveOutUrl(outUrl: String): String? {
        // 1) Follow the /out/ redirect without downloading the partner page.
        try {
            val res = app.get(outUrl, allowRedirects = false)
            val location = res.headers["Location"] ?: res.headers["location"]
            if (!location.isNullOrBlank() && location.startsWith("http")) return location
        } catch (e: Exception) {
            Log.d(name, "out redirect failed: ${e.message}")
        }
        // 2) Decode the l= payload (prefix + base64 http url, url-encoded).
        try {
            val l = Regex("[?&]l=([^&]+)").find(outUrl)?.groupValues?.get(1) ?: return null
            val decoded = URLDecoder.decode(l, "UTF-8")
            val b64 = Regex("(aHR0[^ ]+)").find(decoded)?.groupValues?.get(1)
                ?: Regex("(aHR0.+)").find(decoded)?.groupValues?.get(1)
                ?: return null
            // Payload may concatenate; try longest-first trims on 4-char boundary.
            var candidate = b64.trim()
            while (candidate.length >= 8) {
                try {
                    val text = String(Base64.decode(candidate, Base64.DEFAULT), Charsets.UTF_8)
                    val url = Regex("(https?://[^\\s\"'<>]+)").find(text)?.groupValues?.get(1)
                    if (!url.isNullOrBlank()) return url
                } catch (_: Exception) { }
                candidate = candidate.dropLast(4)
            }
        } catch (e: Exception) {
            Log.d(name, "out decode failed: ${e.message}")
        }
        return null
    }

    private fun domainOf(url: String): String = try {
        java.net.URI(url).host?.removePrefix("www.") ?: name
    } catch (_: Exception) { name }

    private data class TripleFour(val first: String, val second: String, val third: String?, val fourth: String?)

    companion object {
        private const val SEP = "|||"
    }
}
