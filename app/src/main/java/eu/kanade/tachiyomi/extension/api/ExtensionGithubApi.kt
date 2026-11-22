package eu.kanade.tachiyomi.extension.api

import ani.himitsu.others.LanguageMapper
import ani.himitsu.parsers.novel.AvailableNovelSources
import ani.himitsu.parsers.novel.NovelExtension
import ani.himitsu.settings.saving.PrefManager
import ani.himitsu.settings.saving.PrefName
import ani.himitsu.util.Logger
import eu.kanade.tachiyomi.extension.anime.model.AnimeExtension
import eu.kanade.tachiyomi.extension.anime.model.AvailableAnimeSources
import eu.kanade.tachiyomi.extension.manga.model.AvailableMangaSources
import eu.kanade.tachiyomi.extension.manga.model.MangaExtension
import eu.kanade.tachiyomi.extension.util.ExtensionLoader
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.parseAs
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import tachiyomi.core.util.lang.withIOContext
import uy.kohesive.injekt.injectLazy

internal class ExtensionGithubApi {
    private val networkService: NetworkHelper by injectLazy()
    private val json: Json by injectLazy()

    private fun List<ExtensionSourceJsonObject>.toAnimeExtensionSources(): List<AvailableAnimeSources> {
        return this.map {
            AvailableAnimeSources(
                id = it.id,
                lang = it.lang,
                name = it.name,
                baseUrl = it.baseUrl,
            )
        }
    }

    private fun List<ExtensionJsonObject>.toAnimeExtensions(repository: String): List<AnimeExtension.Available> {
        return this
            .filter {
                val libVersion = it.extractLibVersion()
                libVersion >= ExtensionLoader.ANIME_LIB_VERSION_MIN && libVersion <= ExtensionLoader.ANIME_LIB_VERSION_MAX
            }
            .map {
                AnimeExtension.Available(
                    name = it.name.substringAfter("Aniyomi: "),
                    pkgName = it.pkg,
                    versionName = it.version,
                    versionCode = it.code,
                    libVersion = it.extractLibVersion(),
                    lang = it.lang,
                    isNsfw = it.nsfw == 1,
                    hasReadme = it.hasReadme == 1,
                    hasChangelog = it.hasChangelog == 1,
                    sources = it.sources?.toAnimeExtensionSources().orEmpty(),
                    apkName = it.apkUrl.ifEmpty { it.apk },
                    repository = repository,
                    iconUrl = it.iconUrl.ifEmpty { "${repository}/icon/${it.pkg}.png" },
                )
            }
    }

    suspend fun findAnimeExtensions(): List<AnimeExtension.Available> {
        return withIOContext {

            val extensions: ArrayList<AnimeExtension.Available> = arrayListOf()

            val repos =
                PrefManager.getVal<Set<String>>(PrefName.AnimeExtensionRepos).toMutableList()

            repos.map {
                async {
                    try {
                        extensions.addAll(fetchRepoIndex(it).toAnimeExtensions(it))
                    } catch (e: Throwable) {
                        Logger.log("Failed to get extensions from GitHub")
                        Logger.log(e)
                    }
                }
            }.awaitAll()
            extensions
        }
    }

    fun getAnimeApkUrl(extension: AnimeExtension.Available): String {
        if (extension.apkName.startsWith("http")) return extension.apkName
        return "${extension.repository}/apk/${extension.apkName}"
    }

    private fun List<ExtensionSourceJsonObject>.toMangaExtensionSources(): List<AvailableMangaSources> {
        return this.map {
            AvailableMangaSources(
                id = it.id,
                lang = it.lang,
                name = it.name,
                baseUrl = it.baseUrl,
            )
        }
    }

    private fun List<ExtensionJsonObject>.toMangaExtensions(repository: String): List<MangaExtension.Available> {
        return this
            .filter {
                val libVersion = it.extractLibVersion()
                libVersion >= ExtensionLoader.MANGA_LIB_VERSION_MIN && libVersion <= ExtensionLoader.MANGA_LIB_VERSION_MAX
            }
            .map {
                MangaExtension.Available(
                    name = it.name.substringAfter("Tachiyomi: "),
                    pkgName = it.pkg,
                    versionName = it.version,
                    versionCode = it.code,
                    libVersion = it.extractLibVersion(),
                    lang = it.lang,
                    isNsfw = it.nsfw == 1,
                    hasReadme = it.hasReadme == 1,
                    hasChangelog = it.hasChangelog == 1,
                    sources = it.sources?.toMangaExtensionSources().orEmpty(),
                    apkName = it.apkUrl.ifEmpty { it.apk },
                    repository = repository,
                    iconUrl = it.iconUrl.ifEmpty { "${repository}/icon/${it.pkg}.png" },
                )
            }
    }

    suspend fun findMangaExtensions(): List<MangaExtension.Available> {
        return withIOContext {

            val extensions: ArrayList<MangaExtension.Available> = arrayListOf()

            val repos =
                PrefManager.getVal<Set<String>>(PrefName.MangaExtensionRepos).toMutableList()

            repos.map {
                async {
                    try {
                        extensions.addAll(fetchRepoIndex(it).toMangaExtensions(it))
                    } catch (e: Throwable) {
                        Logger.log("Failed to get extensions from GitHub")
                        Logger.log(e)
                    }
                }
            }.awaitAll()
            extensions
        }
    }

    fun getMangaApkUrl(extension: MangaExtension.Available): String {
        if (extension.apkName.startsWith("http")) return extension.apkName
        return "${extension.repository}/apk/${extension.apkName}"
    }

    private fun List<ExtensionSourceJsonObject>.toNovelSources(): List<AvailableNovelSources> {
        return map { source ->
            AvailableNovelSources(
                source.id,
                source.lang,
                source.name,
                source.baseUrl,
            )
        }
    }

    private fun List<ExtensionJsonObject>.toNovelExtensions(repository: String): List<NovelExtension.Available> {
        return mapNotNull { extension ->
            val sources = extension.sources?.map { source ->
                ExtensionSourceJsonObject(
                    source.id,
                    source.lang,
                    source.name,
                    source.baseUrl,
                )
            }
            NovelExtension.Available(
                name = extension.name,
                pkgName = extension.pkg,
                versionName = extension.version,
                versionCode = extension.code,
                isNsfw = extension.nsfw == 1,
                sources = sources?.toNovelSources() ?: emptyList(),
                repository = repository,
                iconUrl = extension.iconUrl.ifEmpty { "${repository}/icon/${extension.pkg}.png" },
            )
        }
    }

    suspend fun findNovelExtensions(): List<NovelExtension.Available> {
        return withIOContext {

            val extensions: ArrayList<NovelExtension.Available> = arrayListOf()

            val repos = PrefManager.getVal<Set<String>>(PrefName.NovelExtensionRepos).toMutableList()

            repos.map {
                async {
                    try {
                        extensions.addAll(fetchRepoIndex(it).toNovelExtensions(it))
                    } catch (e: Throwable) {
                        Logger.log(e)
                    }
                }
            }.awaitAll()
            extensions
        }
    }

    // https://github.com/LNReader/lnreader-plugins/blob/master/docs/plugin-template.ts
    private fun List<PluginJsonObject>.toNovelPlugins(repository: String): List<NovelExtension.Plugin> {
        return this
            .filter {
                it.url.endsWith("[madara].js") || it.iconUrl.contains("/madara/")
            }
            .map { extension ->
                val lang = LanguageMapper.mapNativeToCode(extension.lang) ?: "Multi"
                val sources =
                    listOf(
                        ExtensionSourceJsonObject(
                            extension.id.hashCode().toLong(),
                            lang,
                            extension.name,
                            extension.site,
                        ),
                        ExtensionSourceJsonObject(
                            extension.id.hashCode().toLong(),
                            lang,
                            extension.name,
                            extension.url,
                        )
                    )
                NovelExtension.Plugin(
                    extension.name,
                    "plugin:${extension.id}",
                    extension.version,
                    extension.version.replace(".", "").toLong(),
                    lang,
                    sources.toNovelSources(),
                    repository = repository,
                    iconUrl = extension.iconUrl,
                )
            }
    }

    suspend fun findNovelPlugins(): List<NovelExtension.Plugin> {
        return withIOContext {

            val plugins: ArrayList<NovelExtension.Plugin> = arrayListOf()

            val repos = PrefManager.getVal<Set<String>>(PrefName.NovelExtensionRepos).toMutableList()

            repos.forEach {
                    try {
                        val repoExtensions =
                            networkService.client
                                .newCall(GET("${it}/plugins.min.json"))
                                .awaitSuccess()
                                .parseAs<List<PluginJsonObject>>(json)
                                .toNovelPlugins(it)

                        plugins.addAll(repoExtensions)
                    } catch (ex: Throwable) {
                        Logger.log(ex)
                    }
                }
            plugins
        }
    }



    fun getNovelApkUrl(extension: NovelExtension.Available): String {
        return "${extension.repository}/apk/${extension.pkgName}.apk"
    }


    /**
     * Fetch a repo's extension index. Tries the legacy `index.min.json` first;
     * if it returns a stub (< 10 entries — keiyoushi-style "update your app"
     * placeholders) or fails entirely, falls back to the gzipped protobuf
     * `index.pb` format used by current mihon/keiyoushi repos.
     */
    private suspend fun fetchRepoIndex(repo: String): List<ExtensionJsonObject> {
        val jsonEntries = runCatching {
            val response = try {
                networkService.client
                    .newCall(GET("$repo/index.min.json"))
                    .awaitSuccess()
            } catch (e: Throwable) {
                Logger.log("Failed to get repo: $repo")
                Logger.log(e)
                null
            } ?: networkService.client
                .newCall(GET("${fallbackRepoUrl(repo)}/index.min.json"))
                .awaitSuccess()
            response.parseAs<List<ExtensionJsonObject>>(json)
        }.getOrDefault(emptyList())

        if (jsonEntries.size >= 10) return jsonEntries

        return runCatching {
            val bytes = networkService.client
                .newCall(GET("$repo/index.pb"))
                .awaitSuccess()
                .body.bytes()
            ExtensionIndexPb.parse(bytes).map { e ->
                ExtensionJsonObject(
                    name = e.name,
                    pkg = e.pkg,
                    apk = e.apkUrl.ifEmpty { "${e.pkg}.apk" },
                    lang = e.sources.firstOrNull()?.lang ?: "all",
                    code = e.code,
                    version = e.version,
                    nsfw = if (e.nsfw > 0) 1 else 0,
                    sources = e.sources.map {
                        ExtensionSourceJsonObject(
                            id = it.id,
                            lang = it.lang,
                            name = it.name,
                            baseUrl = it.baseUrl,
                        )
                    },
                    apkUrl = e.apkUrl,
                    iconUrl = e.iconUrl,
                    lib = e.lib,
                )
            }
        }.getOrElse {
            Logger.log("Failed to parse index.pb for $repo")
            Logger.log(it)
            jsonEntries
        }
    }

    private fun fallbackRepoUrl(repoUrl: String): String? {
        var fallbackRepoUrl = "https://gcore.jsdelivr.net/gh/"
        val strippedRepoUrl =
            repoUrl.removePrefix("https://").removePrefix("http://").removeSuffix("/")
        val repoUrlParts = strippedRepoUrl.split("/")
        if (repoUrlParts.size < 3) {
            return null
        }
        val repoOwner = repoUrlParts[1]
        val repoName = repoUrlParts[2]
        fallbackRepoUrl += "$repoOwner/$repoName"
        val repoBranch = if (repoUrlParts.size > 3) {
            repoUrlParts[3]
        } else {
            "main"
        }
        fallbackRepoUrl += "@$repoBranch"
        return fallbackRepoUrl
    }
}

@Serializable
private data class ExtensionJsonObject(
    val name: String,
    val pkg: String,
    val apk: String,
    val lang: String,
    val code: Long,
    val version: String,
    val nsfw: Int,
    val hasReadme: Int = 0,
    val hasChangelog: Int = 0,
    val sources: List<ExtensionSourceJsonObject>?,
    // index.pb (protobuf) fields: full URLs instead of repo-relative names
    val apkUrl: String = "",
    val iconUrl: String = "",
    val lib: String = "",
)

@Serializable
private data class PluginJsonObject(
    val id: String,
    val name: String,
    val site: String,
    val lang: String,
    val version: String,
    val url: String,
    val iconUrl: String,
)

@Serializable
private data class ExtensionSourceJsonObject(
    val id: Long,
    val lang: String,
    val name: String,
    val baseUrl: String,
)

private fun ExtensionJsonObject.extractLibVersion(): Double {
    if (lib.isNotEmpty()) return lib.toDoubleOrNull() ?: 0.0
    return version.substringBeforeLast('.').toDoubleOrNull() ?: 0.0
}
