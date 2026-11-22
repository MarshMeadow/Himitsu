package ani.himitsu.connections.github

import ani.himitsu.Mapper
import ani.himitsu.R
import ani.himitsu.client
import ani.himitsu.settings.Developer
import bit.himitsu.nio.Strings.getString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.decodeFromJsonElement
import java.util.Collections

class Contributors {

    fun getContributors(): Array<Developer> {
        val contributors = arrayListOf<Developer>()
        contributors.addAll(
            arrayOf(
                Developer(
                    "Nekosphere",
                    "https://avatars.githubusercontent.com/u/213000000?v=4",
                    "Developed & maintained by Nekosphere - founded & led by N3K0",
                    "https://github.com/Nekomo-App"
                ),
                Developer(
                    "MoonPic",
                    "https://gitlab.com/uploads/-/system/user/avatar/21385212/avatar.png",
                    "${getString(R.string.himitsu)} Website Maintainer",
                    "https://github.com/moonpic"
                )
            ).toList()
        )
        runBlocking(Dispatchers.IO) {
            try {
                val res = mutableListOf<GithubResponse>()
                listOf("Nekomo-App/HimitsuX", "itsmechinmoy/Dantotsu").forEach { repo ->
                    try {
                        client.get("https://api.github.com/repos/$repo/contributors")
                            .parsed<JsonArray>().map {
                                Mapper.json.decodeFromJsonElement<GithubResponse>(it)
                            }.forEach { entry ->
                                if (res.none { it.login == entry.login }) res.add(entry)
                            }
                    } catch (e: Exception) {
                        ani.himitsu.util.Logger.log(e)
                    }
                }
                res.indexOfFirst { it.login == "AbandonedCart" }
                    .takeIf { it > 0 }?.let { Collections.swap(res, it, 0) }
                res.indexOfFirst { it.login == "rebelonion" }
                    .takeIf { it > 0 }?.let { Collections.swap(res, it, 1) }
                res.filter { it.login != "SunglassJerry" }.forEach {
                    contributors.add(
                        Developer(
                            it.login,
                            it.avatarUrl,
                            when (it.login) {
                                "rebelonion" -> getString(R.string.dev_maintainer, "Dantotsu")
                                "AbandonedCart" -> "${getString(R.string.himitsu)} ${getString(R.string.dev_maintainer, "Himitsu")}"
                                "WaiWhat" -> "Icon Designer"
                                else -> getString(R.string.contributor)
                            },
                            it.htmlUrl
                        )
                    )
                }
            } catch (e: Exception) {
                ani.himitsu.util.Logger.log(e)
            }
        }
        contributors.addAll(
            arrayOf(
                Developer(
                    "MarshMeadow",
                        "https://avatars.githubusercontent.com/u/88599122?v=4",
                        "Beta Icon Designer & Website Maintainer",
                        "https://github.com/MarshMeadow?tab=repositories"
                    ),
                    Developer(
                        "Zaxx69",
                        "https://s4.anilist.co/file/anilistcdn/user/avatar/large/b6342562-kxE8m4i7KUMK.png",
                        "Telegram Admin",
                        "https://anilist.co/user/6342562"
                    ),
                    Developer(
                        "Arif Alam",
                        "https://s4.anilist.co/file/anilistcdn/user/avatar/large/b6011177-2n994qtayiR9.jpg",
                        "Discord & ${getString(R.string.comment_mod)}",
                        "https://anilist.co/user/6011177"
                    ),
                    Developer(
                        "SunglassJeery",
                        "https://s4.anilist.co/file/anilistcdn/user/avatar/large/b5804776-FEKfP5wbz2xv.png",
                        "Head Discord & ${getString(R.string.comment_mod)}",
                        "https://anilist.co/user/5804776"
                    ),
                    Developer(
                        "Excited",
                        "https://s4.anilist.co/file/anilistcdn/user/avatar/large/b6131921-toSoGWmKbRA1.png",
                        getString(R.string.comment_mod),
                        "https://anilist.co/user/6131921"
                    ),
                    Developer(
                        "Gurjshan",
                        "https://s4.anilist.co/file/anilistcdn/user/avatar/large/b6363228-rWQ3Pl3WuxzL.png",
                        getString(R.string.comment_mod),
                        "https://anilist.co/user/6363228"
                    ),
                    Developer(
                        "NekoMimi",
                        "https://s4.anilist.co/file/anilistcdn/user/avatar/large/b6244220-HOpImMGMQAxW.jpg",
                        getString(R.string.comment_mod),
                        "https://anilist.co/user/6244220"
                    ),
                    Developer(
                        "Ziadsenior",
                        "https://s4.anilist.co/file/anilistcdn/user/avatar/large/b6049773-8cjYeUOFUguv.jpg",
                        "${getString(R.string.comment_mod)} & Arabic Translator",
                        "https://anilist.co/user/6049773"
                    ),
                    Developer(
                        "hastsu",
                        "https://cdn.discordapp.com/avatars/602422545077108749/20b4a6efa4314550e4ed51cdbe4fef3d.webp?size=160",
                        "${getString(R.string.comment_mod)} & Arabic Translator",
                        "https://anilist.co/user/6183359"
                    ),
                ).toList()
            )
        return contributors.toTypedArray()
    }


    @Serializable
    data class GithubResponse(
        @SerialName("login")
        val login: String,
        @SerialName("avatar_url")
        val avatarUrl: String,
        @SerialName("html_url")
        val htmlUrl: String
    )
}