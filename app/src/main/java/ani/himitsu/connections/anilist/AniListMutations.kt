package ani.himitsu.connections.anilist

import ani.himitsu.connections.anilist.AniList.executeQuery
import ani.himitsu.connections.anilist.api.FuzzyDate
import ani.himitsu.connections.anilist.api.Query
import ani.himitsu.connections.anilist.api.ToggleLike
import bit.himitsu.nio.Strings.getString
import com.google.gson.Gson
import kotlinx.serialization.json.JsonObject

class AniListMutations {

    suspend fun editList(
        mediaID: Int,
        progress: Int? = null,
        score: Int? = null,
        repeat: Int? = null,
        notes: String? = null,
        status: String? = null,
        private: Boolean? = null,
        startedAt: FuzzyDate? = null,
        completedAt: FuzzyDate? = null,
        customList: List<String>? = null
    ) {

        val query = """
            mutation ( ${"$"}mediaID: Int, ${"$"}progress: Int,${"$"}private:Boolean,${"$"}repeat: Int, ${"$"}notes: String, ${"$"}customLists: [String], ${"$"}scoreRaw:Int, ${"$"}status:MediaListStatus, ${"$"}start:FuzzyDateInput${if (startedAt != null) "=" + startedAt.toVariableString() else ""}, ${"$"}completed:FuzzyDateInput${if (completedAt != null) "=" + completedAt.toVariableString() else ""} ) {
                SaveMediaListEntry( mediaId: ${"$"}mediaID, progress: ${"$"}progress, repeat: ${"$"}repeat, notes: ${"$"}notes, private: ${"$"}private, scoreRaw: ${"$"}scoreRaw, status:${"$"}status, startedAt: ${"$"}start, completedAt: ${"$"}completed , customLists: ${"$"}customLists ) {
                    score(format:POINT_10_DECIMAL) startedAt{year month day} completedAt{year month day}
                }
            }
        """.replace("\n", "").replace("""    """, "")

        val variables = """{"mediaID":$mediaID
            ${if (private != null) ""","private":$private""" else ""}
            ${if (progress != null) ""","progress":$progress""" else ""}
            ${if (score != null) ""","scoreRaw":$score""" else ""}
            ${if (repeat != null) ""","repeat":$repeat""" else ""}
            ${if (notes != null) ""","notes":"${notes.replace("\n", "\\n")}"""" else ""}
            ${if (status != null) ""","status":"$status"""" else ""}
            ${if (customList != null) ""","customLists":[${customList.joinToString { "\"$it\"" }}]""" else ""}
            }""".replace("\n", "").replace("""    """, "")
        println(variables)
        executeQuery<JsonObject>(query, variables, show = true)
    }

    suspend fun deleteList(listId: Int) {
        val query = "mutation(${"$"}id:Int){DeleteMediaListEntry(id:${"$"}id){deleted}}"
        val variables = """{"id":"$listId"}"""
        executeQuery<JsonObject>(query, variables)
    }


    suspend fun rateReview(reviewId: Int, rating: String): Query.RateReviewResponse? {
        val query = "mutation{RateReview(reviewId:$reviewId,rating:$rating){id userId mediaId mediaType summary body(asHtml:true)rating ratingAmount userRating score private siteUrl createdAt updatedAt user{id name bannerImage avatar{medium large}}}}"
        return executeQuery<Query.RateReviewResponse>(query)
    }

    suspend fun postActivity(text:String): String {
        val encodedText = text.sanitizeToGson
        val query = "mutation{SaveTextActivity(text:$encodedText){siteUrl}}"
        val result = executeQuery<JsonObject>(query)
        val errors = result?.get("errors")
        return errors?.toString() ?: getString(ani.himitsu.R.string.success)
    }

    suspend fun deleteActivity(activityId: Int): Boolean {
        val query = "mutation{DeleteActivity(id:$activityId){deleted}}"
        val result = executeQuery<JsonObject>(query)
        return result?.get("errors") == null
//                && result?.get("data")
    }

    suspend fun postReview(summary: String, body: String, mediaId: Int, score: Int): String {
        val encodedSummary = summary.sanitizeToGson
        val encodedBody = body.sanitizeToGson
        val query = "mutation{SaveReview(mediaId:$mediaId,summary:$encodedSummary,body:$encodedBody,score:$score){siteUrl}}"
        val result = executeQuery<JsonObject>(query)
        val errors = result?.get("errors")
        return errors?.toString() ?: getString(ani.himitsu.R.string.success)
    }

    suspend fun deleteReview(activityId: Int): Boolean {
        val query = "mutation{DeleteReview(id:$activityId){deleted}}"
        val result = executeQuery<JsonObject>(query)
        return result?.get("errors") == null
//                && result?.get("data")
    }

    suspend fun postReply(activityId: Int, text: String): String {
        val encodedText = text.sanitizeToGson
        val query = "mutation{SaveActivityReply(activityId:$activityId,text:$encodedText){id}}"
        val result = executeQuery<JsonObject>(query)
        val errors = result?.get("errors")
        return errors?.toString() ?: getString(ani.himitsu.R.string.success)
    }

    suspend fun deleteReply(activityId: Int): Boolean {
        val query = "mutation{DeleteActivityReply(id:$activityId){deleted}}"
        val result = executeQuery<JsonObject>(query)
        return result?.get("errors") == null
//                && result?.get("data")
    }

    suspend fun toggleFav(anime: Boolean = true, id: Int) {
        val query =
            """mutation (${"$"}animeId: Int,${"$"}mangaId:Int) { ToggleFavourite(animeId:${"$"}animeId,mangaId:${"$"}mangaId){ anime { edges { id } } manga { edges { id } } } }"""
        val variables = if (anime) """{"animeId":"$id"}""" else """{"mangaId":"$id"}"""
        executeQuery<JsonObject>(query, variables)
    }

    suspend fun toggleFav(type: FavType, id: Int): Boolean {
        val filter = when (type) {
            FavType.ANIME -> "animeId"
            FavType.MANGA -> "mangaId"
            FavType.CHARACTER -> "characterId"
            FavType.STAFF -> "staffId"
            FavType.STUDIO -> "studioId"
        }
        val query = """mutation{ToggleFavourite($filter:$id){anime{pageInfo{total}}}}"""
        val result = executeQuery<JsonObject>(query)
        return result?.get("errors") == null && result != null
    }

    enum class FavType {
        ANIME, MANGA, CHARACTER, STAFF, STUDIO
    }

    suspend fun toggleLike(id: Int, type: String): ToggleLike? {
        return executeQuery<ToggleLike>(
            """mutation Like{ToggleLikeV2(id:$id,type:$type){__typename}}"""
        )
    }

    suspend fun toggleFollow(id: Int): Query.ToggleFollow? {
        return executeQuery<Query.ToggleFollow>(
            """mutation{ToggleFollow(userId:$id){id, isFollowing, isFollower}}"""
        )
    }

    suspend fun updateSettings(
        timezone: String? = null,
        titleLanguage: String? = null,
        staffNameLanguage: String? = null,
        activityMergeTime: Int? = null,
        airingNotifications: Boolean? = null,
        displayAdultContent: Boolean? = null,
        restrictMessagesToFollowing: Boolean? = null,
        scoreFormat: String? = null,
        rowOrder: String? = null,
        about: String? = null,
    ) {
        val query = """
            mutation (
                ${"$"}timezone: String,
                ${"$"}titleLanguage: UserTitleLanguage,
                ${"$"}staffNameLanguage: UserStaffNameLanguage,
                ${"$"}activityMergeTime: Int,
                ${"$"}airingNotifications: Boolean,
                ${"$"}displayAdultContent: Boolean,
                ${"$"}restrictMessagesToFollowing: Boolean,
                ${"$"}scoreFormat: ScoreFormat,
                ${"$"}rowOrder: String,
                ${"$"}about: String
            ) {
                UpdateUser(
                    timezone: ${"$"}timezone,
                    titleLanguage: ${"$"}titleLanguage,
                    staffNameLanguage: ${"$"}staffNameLanguage,
                    activityMergeTime: ${"$"}activityMergeTime,
                    airingNotifications: ${"$"}airingNotifications,
                    displayAdultContent: ${"$"}displayAdultContent,
                    restrictMessagesToFollowing: ${"$"}restrictMessagesToFollowing,
                    scoreFormat: ${"$"}scoreFormat,
                    rowOrder: ${"$"}rowOrder,
                    about: ${"$"}about,
                ) {
                    id
                    options {
                        timezone
                        titleLanguage
                        staffNameLanguage
                        activityMergeTime
                        airingNotifications
                        displayAdultContent
                        restrictMessagesToFollowing
                    }
                    mediaListOptions {
                      scoreFormat
                      rowOrder
                    }
                }
            }
        """.trimIndent()

        val variables = """
            {
                ${timezone?.let { """"timezone":"$it",""" } ?: ""}
                ${titleLanguage?.let { """"titleLanguage":"$it",""" } ?: ""}
                ${staffNameLanguage?.let { """"staffNameLanguage":"$it",""" } ?: ""}
                ${activityMergeTime?.let { """"activityMergeTime":$it,""" } ?: ""}
                ${airingNotifications?.let { """"airingNotifications":$it,""" } ?: ""}
                ${displayAdultContent?.let { """"displayAdultContent":$it,""" } ?: ""}
                ${restrictMessagesToFollowing?.let { """"restrictMessagesToFollowing":$it,""" } ?: ""}
                ${scoreFormat?.let { """"scoreFormat":"$it",""" } ?: ""}
                ${rowOrder?.let { """"rowOrder":"$it",""" } ?: ""}
                ${about?.let { """"about":${it.sanitizeToGson},""" } ?: ""}
            }
        """.trimIndent().replace("\n", "").replace("""    """, "").replace(",}", "}")

        executeQuery<JsonObject>(query, variables)
    }

    suspend fun updateUserBio(about: String): Boolean {
        val encodedAbout = about.sanitizeToGson
        val query = """
            mutation {
                UpdateUser(about: $encodedAbout) {
                    id
                    about
                }
            }
        """.trimIndent()
        val result = executeQuery<JsonObject>(query)
        return result?.get("errors") == null && result != null
    }

    suspend fun getUserBio(): String? {
        val query = """{User(id:${AniList.userid}){about(asHtml:false)}}"""
        val res = executeQuery<Query.Bio>(query)
        return res?.data?.user?.about
    }

    suspend fun deleteCustomList(name: String, type: String): Boolean {
        val query = """
            mutation (${"$"}name: String, ${"$"}type: MediaType) {
                DeleteCustomList(customList: ${"$"}name, type: ${"$"}type) {
                    deleted
                }
            }
        """.trimIndent()
        val variables = """
            {
                "name": "$name",
                "type": "$type"
            }
        """.trimIndent()
        val result = executeQuery<JsonObject>(query, variables)
        return result?.get("errors") == null
    }

    suspend fun updateCustomLists(
        animeCustomLists: List<String>?,
        mangaCustomLists: List<String>?
    ): Boolean {
        val query = """
            mutation (${"$"}animeListOptions: MediaListOptionsInput, ${"$"}mangaListOptions: MediaListOptionsInput) {
                UpdateUser(animeListOptions: ${"$"}animeListOptions, mangaListOptions: ${"$"}mangaListOptions) {
                    mediaListOptions {
                        animeList {
                            customLists
                        }
                        mangaList {
                            customLists
                        }
                    }
                }
            }
        """.trimIndent()
        val variables = """
            {
                ${animeCustomLists?.let { """"animeListOptions": {"customLists": ${Gson().toJson(it)}},""" } ?: ""}
                ${mangaCustomLists?.let { """"mangaListOptions": {"customLists": ${Gson().toJson(it)}}""" } ?: ""}
            }
        """.trimIndent().replace("\n", "").replace("""    """, "").replace(",}", "}")

        val result = executeQuery<JsonObject>(query, variables)
        return result?.get("errors") == null
    }

    // https://gitlab.com/gobusto/unicodifier/-/blob/master/unicodifier.html#L133-143
    private val String.sanitizeToGson : String get() {
        val sb = StringBuilder()
        var i = 0
        while (i < this.length) {
            val codePoint = this.codePointAt(i)
            if (codePoint > 0xFFFF) {
                sb.append("&#").append(codePoint).append(";")
                i += 2
            } else {
                sb.append(this[i])
                i++
            }
        }
        return Gson().toJson(sb.toString())
    }
}