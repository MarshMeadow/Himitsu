/*
 *   Copyright (c) 2023 Kizzy. All rights reserved.
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */
package ani.himitsu.connections.discord

import ani.himitsu.R
import ani.himitsu.connections.discord.serializers.Activity
import ani.himitsu.connections.discord.serializers.Presence
import ani.himitsu.settings.saving.PrefManager
import ani.himitsu.settings.saving.PrefName
import bit.himitsu.nio.Strings.getString
import eu.kanade.tachiyomi.network.awaitResume
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

@Suppress("MemberVisibilityCanBePrivate")
open class RPC(val token: String) {

    enum class Type {
        PLAYING, STREAMING, LISTENING, WATCHING, COMPETING
    }

    data class Link(val label: String, val url: String)

    @Serializable
    data class Asset(
        @SerialName("external_asset_path")
        val externalAssetPath: String? = null
    )

    companion object {
        private const val TIMEOUT = 15L

        data class RPCData(
            val applicationId: String = Discord.application_Id,
            val type: Type? = null,
            val activityName: String? = null,
            val details: String? = null,
            val state: String? = null,
            val largeImage: Link? = null,
            val smallImage: Link? = null,
            val status: String? = null,
            val startTimestamp: Long? = null,
            val stopTimestamp: Long? = null,
            val buttons: MutableList<Link> = mutableListOf()
        )

        private suspend fun getDiscordImage(
            token: String, data: RPCData, json: Json, imageUrl: String
        ): String? {
            if (imageUrl.startsWith("mp:")) return imageUrl
            val request = Request.Builder()
                .url("https://discord.com/api/v9/applications/${data.applicationId}/external-assets")
                .header("Authorization", token)
                .post("{\"urls\":[\"$imageUrl\"]}".toRequestBody("application/json".toMediaType()))
                .build()
            return runCatching {
                val res = OkHttpClient.Builder()
                    .connectTimeout(TIMEOUT, TimeUnit.SECONDS)
                    .readTimeout(TIMEOUT, TimeUnit.SECONDS)
                    .writeTimeout(TIMEOUT, TimeUnit.SECONDS)
                    .build().newCall(request).awaitResume()
                json.decodeFromString<List<Asset>>(res.body.string())
                    .firstOrNull()?.externalAssetPath?.let { "mp:$it" }
            }.getOrNull()
        }

        suspend fun createPresence(data: RPCData): String {
            val json = Json {
                encodeDefaults = true
                allowStructuredMapKeys = true
                ignoreUnknownKeys = true
            }

            suspend fun String.discordUrl() = Discord.token?.let {
                getDiscordImage(it, data, json, this)
            }

            return json.encodeToString(Presence.Response(
                3,
                Presence(
                    activities = listOf(
                        Activity(
                            name = data.activityName,
                            state = data.state,
                            details = data.details,
                            type = data.type?.ordinal,
                            timestamps = if (data.startTimestamp != null)
                                Activity.Timestamps(data.startTimestamp, data.stopTimestamp)
                            else null,
                            assets = Activity.Assets(
                                largeImage = data.largeImage?.url?.discordUrl(),
                                largeText = data.largeImage?.label,
                                smallImage = if (PrefManager.getVal(PrefName.ShowAniListIcon))
                                    Discord.small_Image_AniList.discordUrl()
                                else
                                    Discord.small_Image.discordUrl(),
                                smallText = if (PrefManager.getVal(PrefName.ShowAniListIcon))
                                    getString(R.string.anilist)
                                else
                                    getString(R.string.app_name),
                            ),
                            buttons = data.buttons.map { it.label },
                            metadata = Activity.Metadata(
                                buttonUrls = data.buttons.map { it.url }
                            ),
                            applicationId = data.applicationId,
                        )
                    ),
                    afk = true,
                    since = data.startTimestamp,
                    status = PrefManager.getVal(PrefName.DiscordStatus)
                )
            ))
        }
    }
}


