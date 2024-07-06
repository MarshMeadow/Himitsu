package ani.himitsu.notifications.anilist

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import ani.himitsu.notifications.AlarmManagerScheduler
import ani.himitsu.notifications.TaskScheduler
import ani.himitsu.settings.saving.PrefManager
import ani.himitsu.settings.saving.PrefName
import ani.himitsu.util.Logger
import kotlinx.coroutines.runBlocking

class AnilistNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        Logger.log("AnilistNotificationReceiver: onReceive")
        runBlocking {
            AnilistNotificationTask().execute(context)
        }
        val anilistInterval =
            AnilistNotificationWorker.checkIntervals[PrefManager.getVal(PrefName.AnilistNotificationInterval)]
        AlarmManagerScheduler(context).scheduleRepeatingTask(
            TaskScheduler.TaskType.ANILIST_NOTIFICATION,
            anilistInterval
        )
    }
}