package com.lagradost.cloudstream3.providers

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URI

class SieuTamPhimProvider : MainAPI() {
    override var mainUrl = "https://www.sieutamphim.org"
    override var name = "Siêu Tầm Phim (VN)"
    override val supportedTypes = setOf(TvType.Anime)
    override val hasMainPage = false
    override val lang = "vi"

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=${query.replace(" ", "+")}"
        val doc = app.get(url).document
        return doc.select("article.post").mapNotNull {
            val title = it.selectFirst("h2 a")?.text()?.trim() ?: return@mapNotNull null
            val href = fixUrl(it.selectFirst("h2 a")?.attr("href") ?: return@mapNotNull null)
            val poster = it.selectFirst("img")?.attr("src")?.let { fixUrl(it) }
            newAnimeSearchResponse(title, href, TvType.Anime) {
                this.posterUrl = poster
                addDubStatus(DubStatus.Dubbed)
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val title = doc.selectFirst("h1")?.text()?.trim() ?: "Unknown"
        val poster = doc.selectFirst(".poster img")?.attr("src")?.let { fixUrl(it) }

        val episodes = mutableListOf<Episode>()
        doc.select("a[href*=/tap-]").forEachIndexed { index, el ->
            val href = fixUrl(el.attr("href"))
            val epText = el.text().trim()
            val epNum = Regex("\\d+").find(epText)?.value?.toIntOrNull() ?: (index + 1)
            episodes.add(Episode(
                data = href,
                name = "Tập $epNum",
                episode = epNum
            ))
        }

        if (episodes.isEmpty()) {
            for (i in 1..193) {
                episodes.add(Episode(
                    data = "$url/tap-$i",
                    name = "Tập $i",
                    episode = i
                ))
            }
        }

        return newAnimeLoadResponse(title, url, TvType.Anime, episodes) {
            this.posterUrl = poster
            this.plot = doc.selectFirst(".description")?.text()
            this.tags = listOf("Hoạt Hình", "Thuyết Minh")
            this.year = 2000
            this.showStatus = ShowStatus.Completed
            addDubStatus(DubStatus.Dubbed)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val res = app.get(data, timeout = 30, headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
            "Referer" to mainUrl
        ), jsEnabled = true)

        val doc: Document = res.document
        val html = res.text

        var iframeSrc = doc.selectFirst("iframe[src*=/player/]")?.attr("src")
            ?: doc.selectFirst("iframe[src*=embed]")?.attr("src")
            ?: run {
                val script = doc.select("script").find { it.html().contains("iframe") || it.html().contains("player") }
                script?.html()?.let {
                    Regex("""src=["']([^"']+)["']""").find(it)?.groupValues?.get(1)
                }
            }

        if (iframeSrc.isNullOrEmpty()) return false
        if (!iframeSrc.startsWith("http")) iframeSrc = "https:$iframeSrc"
        val playerUrl = iframeSrc

        val playerRes = app.get(playerUrl, referer = data, timeout = 30, jsEnabled = true)
        val playerHtml = playerRes.text

        var videoUrl: String? = Regex("""["']file["']\s*:\s*["']([^"']+\.m3u8)""").find(playerHtml)?.groupValues?.get(1)
            ?: Regex("""src["']?\s*:\s*["']([^"']+\.m3u8)""").find(playerHtml)?.groupValues?.get(1)

        if (!videoUrl.isNullOrEmpty()) {
            if (!videoUrl.startsWith("http")) videoUrl = URI(playerUrl).resolve(videoUrl).toString()
            callback.invoke(
                ExtractorLink(
                    source = "SieuTamPhim",
                    name = "HD Thuyết Minh",
                    url = videoUrl,
                    referer = playerUrl,
                    quality = Qualities.Unknown.value,
                    type = ExtractorLinkType.M3U8,
                    headers = mapOf("Referer" to playerUrl)
                )
            )
            return true
        }

        return false
    }

    private fun fixUrl(url: String): String {
        return if (url.startsWith("http")) url else mainUrl + url.removePrefix("/")
    }
}
