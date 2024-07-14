package ani.himitsu

import android.app.Activity
import android.content.Context
import android.os.Bundle
import androidx.multidex.MultiDex
import androidx.multidex.MultiDexApplication
import ani.dantotsu.addons.download.DownloadAddonManager
import ani.dantotsu.addons.torrent.TorrentAddonManager
import ani.himitsu.aniyomi.anime.custom.AppModule
import ani.himitsu.aniyomi.anime.custom.PreferenceModule
import ani.dantotsu.connections.comments.CommentsAPI
import ani.himitsu.notifications.TaskScheduler
import ani.himitsu.parsers.AnimeSources
import ani.himitsu.parsers.MangaSources
import ani.himitsu.parsers.NovelSources
import ani.himitsu.parsers.novel.NovelExtensionManager
import ani.himitsu.settings.saving.PrefManager
import ani.himitsu.settings.saving.PrefName
import ani.himitsu.util.Logger
import bit.himitsu.io.Debug
import bit.himitsu.webkit.ChromeIntegration
import com.google.android.material.color.DynamicColors
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.extension.anime.AnimeExtensionManager
import eu.kanade.tachiyomi.extension.manga.MangaExtensionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import logcat.AndroidLogcatLogger
import logcat.LogPriority
import logcat.LogcatLogger
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.PrintWriter
import java.io.StringWriter


class Himitsu : MultiDexApplication() {
    private lateinit var animeExtensionManager: AnimeExtensionManager
    private lateinit var mangaExtensionManager: MangaExtensionManager
    private lateinit var novelExtensionManager: NovelExtensionManager
    private lateinit var torrentAddonManager: TorrentAddonManager
    private lateinit var downloadAddonManager: DownloadAddonManager

    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        MultiDex.install(this)
    }

    init {
        instance = this
    }

    val mFTActivityLifecycleCallbacks = FTActivityLifecycleCallbacks()

    @OptIn(DelicateCoroutinesApi::class)
    override fun onCreate() {
        super.onCreate()

        PrefManager.init(this)
        Injekt.importModule(AppModule(this))
        Injekt.importModule(PreferenceModule(this))

        val layouts = PrefManager.getVal<List<Boolean>>(PrefName.HomeLayout)
        if (layouts.size == 8) PrefManager.setVal(PrefName.HomeLayout, listOf(false).plus(layouts))

        val useMaterialYou: Boolean = PrefManager.getVal(PrefName.UseMaterialYou)
        if (useMaterialYou) {
            DynamicColors.applyToActivitiesIfAvailable(this)
        }
        registerActivityLifecycleCallbacks(mFTActivityLifecycleCallbacks)

        Thread.setDefaultUncaughtExceptionHandler { _: Thread?, error: Throwable ->
            Logger.log(error)
            val exception = StringWriter().apply {
                error.printStackTrace(PrintWriter(this))
            }
            try {
                Debug.clipException(this, exception.toString())
            } catch (ignored: Exception) { }
            try {
                Debug.saveException(this, exception.toString())
            } catch (ignored: Exception) { }
            android.os.Process.killProcess(android.os.Process.myPid())
            kotlin.system.exitProcess(-1)
        }
        Logger.init(this)

        initializeNetwork()

        setupNotificationChannels()
        if (!LogcatLogger.isInstalled) {
            LogcatLogger.install(AndroidLogcatLogger(LogPriority.VERBOSE))
        }

        CoroutineScope(Dispatchers.IO).launch { loadAnimeExtensions() }
        CoroutineScope(Dispatchers.IO).launch { loadMangaExtensions() }
        CoroutineScope(Dispatchers.IO).launch { loadNovelExtensions() }
        CoroutineScope(Dispatchers.IO).launch {
            downloadAddonManager = Injekt.get()
            downloadAddonManager.init()
            torrentAddonManager = Injekt.get()
            torrentAddonManager.init()
        }
        if (PrefManager.getVal(PrefName.CommentsOptIn)) {
            CoroutineScope(Dispatchers.IO).launch { CommentsAPI.fetchAuthToken(this@Himitsu) }
        }

        GlobalScope.launch {
            val useAlarmManager = PrefManager.getVal<Boolean>(PrefName.UseAlarmManager)
            val scheduler = TaskScheduler.create(this@Himitsu, useAlarmManager)
            try {
                scheduler.scheduleAllTasks(this@Himitsu)
            } catch (e: IllegalStateException) {
                Logger.log("Failed to schedule tasks")
                Logger.log(e)
            }
        }

        CoroutineScope(Dispatchers.IO).launch {
            ChromeIntegration.bindCustomTabService(this@Himitsu)
        }
    }

    private suspend fun loadAnimeExtensions() {
        animeExtensionManager = Injekt.get()
        animeExtensionManager.findAvailableExtensions()
        AnimeSources.init(animeExtensionManager.installedExtensionsFlow)
    }

    private suspend fun loadMangaExtensions() {
        mangaExtensionManager = Injekt.get()
        mangaExtensionManager.findAvailableExtensions()
        MangaSources.init(mangaExtensionManager.installedExtensionsFlow)
    }

    private suspend fun loadNovelExtensions() {
        novelExtensionManager = Injekt.get()
        novelExtensionManager.findAvailableExtensions()
        novelExtensionManager.findAvailablePlugins()
        NovelSources.init(novelExtensionManager.installedExtensionsFlow)
    }

    private fun setupNotificationChannels() {
        try {
            Notifications.createChannels(this)
        } catch (e: Exception) {
            Logger.log("Failed to modify notification channels")
            Logger.log(e)
        }
    }

    inner class FTActivityLifecycleCallbacks : ActivityLifecycleCallbacks {
        var currentActivity: Activity? = null
        override fun onActivityCreated(p0: Activity, p1: Bundle?) {}
        override fun onActivityStarted(p0: Activity) {
            currentActivity = p0
        }

        override fun onActivityResumed(p0: Activity) {
            currentActivity = p0
        }

        override fun onActivityPaused(p0: Activity) {}
        override fun onActivityStopped(p0: Activity) {}
        override fun onActivitySaveInstanceState(p0: Activity, p1: Bundle) {}
        override fun onActivityDestroyed(p0: Activity) {}
    }

    companion object {
        /** Reference to the application instance.
         *
         * USE WITH EXTREME CAUTION!**/
        @JvmStatic
        lateinit var instance: Himitsu
            private set

        fun currentContext(): Context {
            return instance.mFTActivityLifecycleCallbacks.currentActivity
                ?: instance.applicationContext
        }

        fun currentActivity(): Activity? {
            return instance.mFTActivityLifecycleCallbacks.currentActivity
        }
    }
}
