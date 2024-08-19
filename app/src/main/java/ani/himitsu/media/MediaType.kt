package ani.himitsu.media

import ani.himitsu.R
import bit.himitsu.nio.Strings.getString

interface Type {
    fun asText(): String
    fun asLocalizedText(): String

    companion object {
        fun fromText(string: String): Type? {
            return when (string.lowercase()) {
                "anime" -> MediaType.ANIME
                "manga" -> MediaType.MANGA
                "novel" -> MediaType.NOVEL
                "download" -> AddonType.DOWNLOAD
                else -> { null }
            }
        }
    }

    val text: String get() = this.asText()
}

enum class MediaType : Type {
    ANIME,
    MANGA,
    NOVEL;

    override fun asText(): String {
        return when (this) {
            ANIME -> "Anime"
            MANGA -> "Manga"
            NOVEL -> "Novel"
        }
    }

    override fun asLocalizedText(): String {
        return when (this) {
            ANIME -> getString(R.string.anime)
            MANGA -> getString(R.string.manga)
            NOVEL -> getString(R.string.novel)
        }
    }
}

enum class AddonType : Type {
    DOWNLOAD;

    override fun asText(): String {
        return when (this) {
            DOWNLOAD -> "Download"
        }
    }

    override fun asLocalizedText(): String {
        return when (this) {
            DOWNLOAD -> getString(R.string.download)
        }
    }
}
