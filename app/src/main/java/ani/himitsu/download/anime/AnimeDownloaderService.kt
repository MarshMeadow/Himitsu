package ani.himitsu.download.anime

import android.Manifest
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.media3.common.util.UnstableApi
import ani.himitsu.FileUrl
import ani.himitsu.R
import ani.dantotsu.addons.download.DownloadAddonManager
import ani.himitsu.defaultHeaders
import ani.himitsu.download.DownloadedType
import ani.himitsu.download.DownloadsManager
import ani.himitsu.download.DownloadsManager.Companion.getSubDirectory
import ani.himitsu.download.anime.AnimeDownloaderService.AnimeDownloadTask.Companion.getTaskName
import ani.himitsu.download.findValidName
import ani.himitsu.media.cereal.Media
import ani.himitsu.media.MediaType
import ani.himitsu.media.anime.AnimeWatchFragment
import ani.himitsu.parsers.Video
import ani.himitsu.settings.saving.PrefManager
import ani.himitsu.settings.saving.PrefName
import ani.himitsu.isWifiConnected
import ani.himitsu.snackString
import ani.himitsu.toast
import ani.himitsu.util.Logger
import com.anggrayudi.storage.file.forceDelete
import com.anggrayudi.storage.file.openOutputStream
import com.google.gson.GsonBuilder
import com.google.gson.InstanceCreator
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SAnimeImpl
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.SEpisodeImpl
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SChapterImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.net.HttpURLConnection
import java.net.URL
import java.util.Queue
import java.util.concurrent.ConcurrentLinkedQueue


class AnimeDownloaderService : Service() {

    private lateinit var notificationManager: NotificationManagerCompat
    private lateinit var builder: NotificationCompat.Builder
    private val downloadsManager: DownloadsManager = Injekt.get<DownloadsManager>()

    private val downloadJobs = mutableMapOf<String, Job>()
    private val mutex = Mutex()
    private var isCurrentlyProcessing = false
    private var currentTasks: MutableList<AnimeDownloadTask> = mutableListOf()
    private val ffExtension = Injekt.get<DownloadAddonManager>().extension?.extension

    override fun onBind(intent: Intent?): IBinder? {
        // This is only required for bound services.
        return null
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        if (ffExtension == null) {
            toast(getString(R.string.download_addon_not_found))
            stopSelf()
            return
        }
        notificationManager = NotificationManagerCompat.from(this)
        builder =
            NotificationCompat.Builder(this, Notifications.CHANNEL_DOWNLOADER_PROGRESS).apply {
                setContentTitle("Anime Download Progress")
                setSmallIcon(R.drawable.ic_download_24)
                priority = NotificationCompat.PRIORITY_DEFAULT
                setOnlyAlertOnce(true)
                setProgress(100, 0, false)
            }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                builder.build(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, builder.build())
        }
        ContextCompat.registerReceiver(
            this,
            cancelReceiver,
            IntentFilter(ACTION_CANCEL_DOWNLOAD),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
        AnimeServiceDataSingleton.downloadQueue.clear()
        downloadJobs.clear()
        AnimeServiceDataSingleton.isServiceRunning = false
        unregisterReceiver(cancelReceiver)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        snackString("Download started")
        serviceScope.launch {
            mutex.withLock {
                if (!isCurrentlyProcessing) {
                    isCurrentlyProcessing = true
                    processQueue()
                    isCurrentlyProcessing = false
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun processQueue() {
        serviceScope.launch {
            val maxParallel = PrefManager.getVal<Int>(PrefName.MaxParallelDownloads).coerceIn(0, 10)
            val semaphore = Semaphore(if (maxParallel > 0) maxParallel else 1)
            val activeJobs = mutableListOf<Job>()
            while (AnimeServiceDataSingleton.downloadQueue.isNotEmpty()) {
                val task = AnimeServiceDataSingleton.downloadQueue.poll()
                if (task != null) {
                    if (PrefManager.getVal<Boolean>(PrefName.DownloadWifiOnly) &&
                        !isWifiConnected(this@AnimeDownloaderService)
                    ) {
                        broadcastDownloadFailed(task.episode)
                        continue
                    }
                    val job = launch {
                        semaphore.withPermit {
                            currentTasks.add(task)
                            try {
                                download(task)
                            } finally {
                                currentTasks.remove(task)
                                mutex.withLock {
                                    downloadJobs.remove(task.getTaskName())
                                }
                                updateNotification()
                            }
                        }
                    }
                    mutex.withLock {
                        downloadJobs[task.getTaskName()] = job
                    }
                    activeJobs.add(job)
                }
            }
            activeJobs.joinAll()
            withContext(Dispatchers.Main) {
                stopSelf()
            }
        }
    }

    @UnstableApi
    fun cancelDownload(taskName: String) {
        val sessionIds =
            AnimeServiceDataSingleton.downloadQueue.filter { it.getTaskName() == taskName }
                .map { it.sessionId }.toMutableList()
        sessionIds.addAll(currentTasks.filter { it.getTaskName() == taskName }.map { it.sessionId })
        sessionIds.forEach {
            ffExtension!!.cancelDownload(it)
        }
        currentTasks.removeAll { it.getTaskName() == taskName }
        serviceScope.launch {
            mutex.withLock {
                downloadJobs[taskName]?.cancel()
                downloadJobs.remove(taskName)
                AnimeServiceDataSingleton.downloadQueue.removeAll { it.getTaskName() == taskName }
                updateNotification() // Update the notification after cancellation
            }
        }
    }

    private fun updateNotification() {
        // Update the notification to reflect the current state of the queue
        val pendingDownloads = AnimeServiceDataSingleton.downloadQueue.size
        val text = if (pendingDownloads > 0) {
            "Pending downloads: $pendingDownloads"
        } else {
            "All downloads completed"
        }
        builder.setContentText(text)
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        notificationManager.notify(NOTIFICATION_ID, builder.build())
    }

    /**
     * Download an HLS stream by fetching the playlist and concatenating its
     * segments into a single MPEG-TS file. Used for http:// URLs, which the
     * download add-on cannot handle because it always passes -tls_verify.
     */
    private suspend fun downloadHlsToTemp(
        task: AnimeDownloadTask,
        outputDir: DocumentFile,
        onProgress: suspend (Int) -> Unit
    ): DocumentFile = withContext(Dispatchers.IO) {
        fun open(u: URL): HttpURLConnection = (u.openConnection() as HttpURLConnection).apply {
            task.video.file.headers.forEach { (k, v) -> setRequestProperty(k, v) }
            connectTimeout = 20_000
            readTimeout = 30_000
        }

        val base = URL(task.video.file.url)
        val playlist = open(base).let { conn ->
            try {
                conn.inputStream.bufferedReader().use { it.readText() }
            } finally {
                conn.disconnect()
            }
        }
        if (playlist.contains("#EXT-X-KEY")) {
            throw Exception("Encrypted HLS is not supported for http downloads")
        }
        val segments = playlist.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { URL(base, it) }
        if (segments.isEmpty()) throw Exception("Playlist has no segments")

        val temp = outputDir.createFile(
            "video/mp2t",
            "tmp_${task.getTaskName().findValidName()}.ts"
        ) ?: throw Exception("Failed to create temp file")

        try {
            contentResolver.openOutputStream(temp.uri, "w")?.buffered()?.use { out ->
                segments.forEachIndexed { i, seg ->
                    coroutineContext.ensureActive()
                    val conn = open(seg)
                    try {
                        conn.inputStream.use { it.copyTo(out, 64 * 1024) }
                    } finally {
                        conn.disconnect()
                    }
                    onProgress(((i + 1) * 90) / segments.size)
                }
            } ?: throw Exception("Failed to open temp output stream")
        } catch (e: Exception) {
            temp.forceDelete(this@AnimeDownloaderService)
            throw e
        }
        temp
    }

    @androidx.annotation.OptIn(UnstableApi::class)
    suspend fun download(task: AnimeDownloadTask) = withContext(Dispatchers.IO) {
        try {
            val notifi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(
                    this@AnimeDownloaderService,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                true
            }

            builder.setContentText("Downloading ${getTaskName(task.title, task.episode)}")
            if (notifi) {
                notificationManager.notify(NOTIFICATION_ID, builder.build())
            }

            val outputDir = getSubDirectory(
                this@AnimeDownloaderService,
                MediaType.ANIME,
                false,
                task.title,
                task.episode
            ) ?: throw Exception("Failed to create output directory")

            outputDir.findFile("${task.getTaskName().findValidName()}.mkv")?.delete()
            val outputFile =
                outputDir.createFile("video/x-matroska", "${task.getTaskName()}.mkv")
                    ?: throw Exception("Failed to create output file")

            var percent = 0
            var totalLength = 0.0
            val path = ffExtension!!.setDownloadPath(
                this@AnimeDownloaderService,
                outputFile.uri
            )
            if (!task.video.file.headers.containsKey("User-Agent")
                && !task.video.file.headers.containsKey("user-agent")
            ) {
                val newHeaders = task.video.file.headers.toMutableMap()
                newHeaders["User-Agent"] = defaultHeaders["User-Agent"]!!
                task.video.file.headers = newHeaders
            }

            var hlsTemp: DocumentFile? = null
            val ffTask = if (task.video.file.url.startsWith("http://", true)) {
                // The download add-on always passes -tls_verify to ffmpeg, which is
                // fatal for non-TLS inputs (e.g. extension localhost proxy URLs like
                // http://127.0.0.1/pl/N). Fetch the HLS stream ourselves and let the
                // add-on remux the concatenated .ts instead.
                hlsTemp = downloadHlsToTemp(task, outputDir) { p ->
                    percent = p
                    builder.setProgress(100, percent.coerceAtMost(99), false)
                    broadcastDownloadProgress(task.episode, percent.coerceAtMost(99))
                    if (notifi) {
                        withContext(Dispatchers.Main) {
                            notificationManager.notify(NOTIFICATION_ID, builder.build())
                        }
                    }
                }
                val tempRead =
                    ffExtension!!.getReadPath(this@AnimeDownloaderService, hlsTemp!!.uri)
                ffExtension.customFFMpeg("1", listOf(tempRead, path)) {
                    Logger.log(it)
                }
            } else {
                ffExtension.executeFFProbe(
                    task.video.file.url,
                    task.video.file.headers
                ) {
                    if (it.toDoubleOrNull() != null) {
                        totalLength = it.toDouble()
                    }
                }
                ffExtension.executeFFMpeg(
                    task.video.file.url,
                    path,
                    task.video.file.headers,
                    task.subtitle,
                    task.audio,
                ) {
                    // CALLED WHEN SESSION GENERATES STATISTICS
                    val timeInMilliseconds = it
                    if (timeInMilliseconds > 0 && totalLength > 0) {
                        percent = ((it / 1000) / totalLength * 100).toInt()
                    }
                    Logger.log("Statistics: $it")
                }
            }
            task.sessionId = ffTask
            currentTasks.find { it.getTaskName() == task.getTaskName() }?.sessionId =
                ffTask

            saveMediaInfo(task)

            // periodically check if the download is complete
            while (ffExtension.getState(ffTask) != "COMPLETED") {
                if (ffExtension.getState(ffTask) == "FAILED") {
                    Logger.log("Download failed")
                    builder.setContentText(
                        "${
                            getTaskName(
                                task.title,
                                task.episode
                            )
                        } Download failed"
                    )
                    withContext(Dispatchers.Main) {
                        notificationManager.notify(NOTIFICATION_ID, builder.build())
                        toast("${getTaskName(task.title, task.episode)} Download failed")
                    }
                    downloadsManager.removeDownload(
                        DownloadedType(
                            task.title,
                            task.episode,
                            MediaType.ANIME,
                        ),
                        false
                    ) {}
                    Logger.log(
                        Exception(
                            "Anime Download failed:" +
                                    " ${getTaskName(task.title, task.episode)}" +
                                    " url: ${task.video.file.url}" +
                                    " title: ${task.title}" +
                                    " episode: ${task.episode}" +
                                    "\nffmpeg: ${ffExtension.getStackTrace(ffTask)}"
                        )
                    )
                    currentTasks.removeAll { it.getTaskName() == task.getTaskName() }
                    hlsTemp?.forceDelete(this@AnimeDownloaderService)
                    broadcastDownloadFailed(task.episode)
                    break
                }
                builder.setProgress(
                    100, percent.coerceAtMost(99),
                    false
                )
                broadcastDownloadProgress(
                    task.episode,
                    percent.coerceAtMost(99)
                )
                if (notifi) {
                    withContext(Dispatchers.Main) {
                        notificationManager.notify(NOTIFICATION_ID, builder.build())
                    }
                }
                kotlinx.coroutines.delay(2000)
            }
            if (ffExtension.getState(ffTask) == "COMPLETED") {
                if (ffExtension.hadError(ffTask)) {
                    Logger.log("Download failed")
                    builder.setContentText(
                        "${
                            getTaskName(
                                task.title,
                                task.episode
                            )
                        } Download failed"
                    )
                    withContext(Dispatchers.Main) {
                        notificationManager.notify(NOTIFICATION_ID, builder.build())
                        snackString("${getTaskName(task.title, task.episode)} Download failed")
                    }
                    downloadsManager.removeDownload(
                        DownloadedType(
                            task.title,
                            task.episode,
                            MediaType.ANIME,
                        )
                    ) {}
                    Logger.log(
                        Exception(
                            "Anime Download failed:" +
                                    " ${getTaskName(task.title, task.episode)}" +
                                    " url: ${task.video.file.url}" +
                                    " title: ${task.title}" +
                                    " episode: ${task.episode}" +
                                    "\nffmpeg: ${ffExtension.getStackTrace(ffTask)}"
                        )
                    )
                    currentTasks.removeAll { it.getTaskName() == task.getTaskName() }
                    hlsTemp?.forceDelete(this@AnimeDownloaderService)
                    broadcastDownloadFailed(task.episode)
                    return@withContext
                }
                Logger.log("Download completed")
                builder.setContentText(
                    "${
                        getTaskName(
                            task.title,
                            task.episode
                        )
                    } Download completed"
                )
                withContext(Dispatchers.Main) {
                    notificationManager.notify(NOTIFICATION_ID, builder.build())
                    snackString("${getTaskName(task.title, task.episode)} Download completed")
                }
                PrefManager.getAnimeDownloadPreferences().edit().putString(
                    task.getTaskName(),
                    task.video.file.url
                ).apply()
                downloadsManager.addDownload(
                    DownloadedType(
                        task.title,
                        task.episode,
                        MediaType.ANIME,
                    )
                )

                currentTasks.removeAll { it.getTaskName() == task.getTaskName() }
                hlsTemp?.forceDelete(this@AnimeDownloaderService)
                broadcastDownloadFinished(task.episode)
            } else throw Exception("Download failed")
        } catch (e: Exception) {
            if (e.message?.contains("Coroutine was cancelled") == false) {  //wut
                Logger.log("Exception while downloading file: ${e.message}")
                snackString("Exception while downloading file: ${e.message}")
                Logger.log(e)
            }
            broadcastDownloadFailed(task.episode)
        }
    }

    private fun saveMediaInfo(task: AnimeDownloadTask) {
        serviceScope.launch {
            val directory =
                getSubDirectory(this@AnimeDownloaderService, MediaType.ANIME, false, task.title)
                    ?: throw Exception("Directory not found")
            directory.findFile("media.json")?.forceDelete(this@AnimeDownloaderService)
            val file = directory.createFile("application/json", "media.json")
                ?: throw Exception("File not created")
            val episodeDirectory =
                getSubDirectory(
                    this@AnimeDownloaderService,
                    MediaType.ANIME,
                    false,
                    task.title,
                    task.episode
                )
                    ?: throw Exception("Directory not found")

            val gson = GsonBuilder()
                .registerTypeAdapter(SChapter::class.java, InstanceCreator<SChapter> {
                    SChapterImpl() // Provide an instance of SChapterImpl
                })
                .registerTypeAdapter(SAnime::class.java, InstanceCreator<SAnime> {
                    SAnimeImpl() // Provide an instance of SAnimeImpl
                })
                .registerTypeAdapter(SEpisode::class.java, InstanceCreator<SEpisode> {
                    SEpisodeImpl() // Provide an instance of SEpisodeImpl
                })
                .create()
            val mediaJson = gson.toJson(task.sourceMedia)
            val media = gson.fromJson(mediaJson, Media::class.java)
            if (media != null) {
                media.cover = media.cover?.let { downloadImage(it, directory, "cover.jpg") }
                media.banner = media.banner?.let { downloadImage(it, directory, "banner.jpg") }
                if (task.episodeImage != null) {
                    media.anime?.episodes?.get(task.episode)?.let { episode ->
                        episode.thumb = downloadImage(
                            task.episodeImage,
                            episodeDirectory,
                            "episodeImage.jpg"
                        )?.let {
                            FileUrl(
                                it
                            )
                        }
                    }
                    downloadImage(task.episodeImage, episodeDirectory, "episodeImage.jpg")
                }

                val jsonString = gson.toJson(media)
                withContext(Dispatchers.Main) {
                    try {
                        file.openOutputStream(this@AnimeDownloaderService, false).use { output ->
                            if (output == null) throw Exception("Output stream is null")
                            output.write(jsonString.toByteArray())
                        }
                    } catch (e: android.system.ErrnoException) {
                        Logger.log(e)
                        Toast.makeText(
                            this@AnimeDownloaderService,
                            "Error while saving: ${e.localizedMessage}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }
    }

    private suspend fun downloadImage(url: String, directory: DocumentFile, name: String): String? =
        withContext(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            println("Downloading url $url")
            try {
                connection = URL(url).openConnection() as HttpURLConnection
                connection.connect()
                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    throw Exception("Server returned HTTP ${connection.responseCode} ${connection.responseMessage}")
                }

                directory.findFile(name)?.forceDelete(this@AnimeDownloaderService)
                val file =
                    directory.createFile("image/jpeg", name) ?: throw Exception("File not created")
                file.openOutputStream(this@AnimeDownloaderService, false).use { output ->
                    if (output == null) throw Exception("Output stream is null")
                    connection.inputStream.use { input ->
                        input.copyTo(output)
                    }
                }
                return@withContext file.uri.toString()
            } catch (e: Exception) {
                Logger.log(e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@AnimeDownloaderService,
                        "Exception while saving ${name}: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
                null
            } finally {
                connection?.disconnect()
            }
        }

    private fun broadcastDownloadStarted(episodeNumber: String) {
        val intent = Intent(AnimeWatchFragment.ACTION_DOWNLOAD_STARTED).apply {
            setPackage(packageName)
            putExtra(AnimeWatchFragment.EXTRA_EPISODE_NUMBER, episodeNumber)
        }
        sendBroadcast(intent)
    }

    private fun broadcastDownloadFinished(episodeNumber: String) {
        val intent = Intent(AnimeWatchFragment.ACTION_DOWNLOAD_FINISHED).apply {
            setPackage(packageName)
            putExtra(AnimeWatchFragment.EXTRA_EPISODE_NUMBER, episodeNumber)
        }
        sendBroadcast(intent)
    }

    private fun broadcastDownloadFailed(episodeNumber: String) {
        val intent = Intent(AnimeWatchFragment.ACTION_DOWNLOAD_FAILED).apply {
            setPackage(packageName)
            putExtra(AnimeWatchFragment.EXTRA_EPISODE_NUMBER, episodeNumber)
        }
        sendBroadcast(intent)
    }

    private fun broadcastDownloadProgress(episodeNumber: String, progress: Int) {
        val intent = Intent(AnimeWatchFragment.ACTION_DOWNLOAD_PROGRESS).apply {
            setPackage(packageName)
            putExtra(AnimeWatchFragment.EXTRA_EPISODE_NUMBER, episodeNumber)
            putExtra("progress", progress)
        }
        sendBroadcast(intent)
    }

    private val cancelReceiver = object : BroadcastReceiver() {
        @androidx.annotation.OptIn(UnstableApi::class)
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_CANCEL_DOWNLOAD) {
                val taskName = intent.getStringExtra(EXTRA_TASK_NAME)
                taskName?.let {
                    cancelDownload(it)
                }
            }
        }
    }


    data class AnimeDownloadTask(
        val title: String,
        val episode: String,
        val video: Video,
        val subtitle: List<Pair<String, String>> = emptyList(),
        val audio: List<Pair<String, String>> = emptyList(),
        val sourceMedia: Media? = null,
        val episodeImage: String? = null,
        val retries: Int = 2,
        val simultaneousDownloads: Int = 2,
        var sessionId: Long = -1
    ) {
        fun getTaskName(): String {
            return "${title.replace("/", "")}/${episode.replace("/", "")}"
        }

        companion object {
            fun getTaskName(title: String, episode: String): String {
                return "${title.replace("/", "")}/${episode.replace("/", "")}"
            }
        }
    }

    companion object {
        private const val NOTIFICATION_ID = 1103
        const val ACTION_CANCEL_DOWNLOAD = "action_cancel_download"
        const val EXTRA_TASK_NAME = "extra_task_name"
    }
}

object AnimeServiceDataSingleton {
    var video: Video? = null
    var downloadQueue: Queue<AnimeDownloaderService.AnimeDownloadTask> = ConcurrentLinkedQueue()

    @Volatile
    var isServiceRunning: Boolean = false
}