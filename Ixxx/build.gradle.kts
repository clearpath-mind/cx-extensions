version = 1

cloudstream {
    authors = listOf("clearpath-mind")
    language = "en"
    description = "Ixxx - ixxx.com aggregator (popular, new, top rated + categories)"

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     **/
    status = 1 // will be 3 if unspecified
    tvTypes = listOf("NSFW")
    iconUrl = "https://t3.gstatic.com/faviconV2?client=SOCIAL&type=FAVICON&fallback_opts=TYPE,SIZE,URL&url=https://www.ixxx.com&size=128"
}
