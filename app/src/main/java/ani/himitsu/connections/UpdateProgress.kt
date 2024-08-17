package ani.himitsu.connections

import ani.himitsu.R
import ani.himitsu.Refresh
import ani.himitsu.connections.anilist.AniList
import ani.himitsu.connections.mal.MAL
import ani.himitsu.media.cereal.Media
import ani.himitsu.settings.saving.PrefManager
import ani.himitsu.settings.saving.PrefName
import ani.himitsu.toast
import bit.himitsu.nio.Strings.getString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

fun updateProgress(media: Media, number: String) {
    val incognito: Boolean = PrefManager.getVal(PrefName.Incognito)
    if (!incognito) {
        if (AniList.userid != null) {
            val a = number.toFloatOrNull()?.toInt() ?: 0
            if (a > (media.userProgress ?: -1)) {
                CoroutineScope(Dispatchers.IO).launch {
                    AniList.mutation.editList(
                        media.id,
                        a,
                        status = if (media.userStatus == "REPEATING") media.userStatus else "CURRENT"
                    )
                    media.userProgress = a
                    Refresh.all()
                }
                CoroutineScope(Dispatchers.IO).launch {
                    MAL.query.editList(
                        media.idMAL,
                        media.anime != null,
                        a, null,
                        if (media.userStatus == "REPEATING") media.userStatus!! else "CURRENT"
                    )
                }
                toast(
                    getString(R.string.setting_progress, media.userPreferredName, a)
                )
            }
        } else {
            toast(R.string.login_anilist_account)
        }
    } else {
        toast("Sneaky sneaky :3")
    }
}
