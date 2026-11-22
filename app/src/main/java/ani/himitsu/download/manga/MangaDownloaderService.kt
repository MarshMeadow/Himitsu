package ani.himitsu.download.manga

import android.Manifest
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.os.Build
import android.os.IBinder
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import ani.himitsu.R
import ani.himitsu.download.DownloadedType
import ani.himitsu.download.DownloadsManager
import ani.himitsu.download.DownloadsManager.Companion.getSubDirectory
import ani.himitsu.media.cereal.Media
import ani.himitsu.media.MediaType
import ani.himitsu.media.manga.ImageData
import ani.himitsu.media.manga.MangaReadFragment.Companion.ACTION_DOWNLOAD_FAILED
import ani.himitsu.media.manga.MangaReadFragment.Companion.ACTION_DOWNLOAD_FINISHED
import ani.himitsu.media.manga.MangaReadFragment.Companion.ACTION_DOWNLOAD_PROGRESS
import ani.himitsu.media.manga.MangaReadFragment.Companion.ACTION_DOWNLOAD_STARTED
import ani.himitsu.media.manga.MangaReadFragment.Companion.EXTRA_CHAPTER_NUMBER
import ani.himitsu.isWifiConnected
import ani.himitsu.settings.saving.PrefManager
import ani.himitsu.settings.saving.PrefName
import ani.himitsu.snackString
import ani.himitsu.util.Logger
import com.anggrayudi.storage.file.deleteRecursively
import com.anggrayudi.storage.file.forceDelete
import com.anggrayudi.storage.file.openOutputStream
import com.google.gson.GsonBuilder
import com.google.gson.InstanceCreator
import eu.kanade.tachiyomi.data.notification.Notifications.CHANNEL_DOWNLOADER_PROGRESS
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SChapterImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import tachiyomi.core.util.lang.launchIO
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.net.HttpURLConnection
import java.net.URL
import java.util.Queue
import java.util.concurrent.ConcurrentLinkedQueue

class MangaDownloaderService : Service() {

    private lateinit var notificationManager: NotificationManagerCompat
    private lateinit var builder: NotificationCompat.Builder
    private val downloadsManager: DownloadsManager = Injekt.get<DownloadsManager>()

    private val downloadJobs = mutableMapOf<String, Job>()
    private val mutex = Mutex()
    private var isCurrentlyProcessing = false

    override fun onBind(intent: Intent?): IBinder? {
        // This is only required for bound services.
        return null
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        notificationManager = NotificationManagerCompat.from(this)
        builder = NotificationCompat.Builder(this, CHANNEL_DOWNLOADER_PROGRESS).apply {
            setContentTitle("Manga Download Progress")
            setSmallIcon(R.drawable.ic_download_24)
            priority = NotificationCompat.PRIORITY_DEFAULT
            setOnlyAlertOnce(true)
            setProgress(0, 0, false)
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
        MangaServiceDataSingleton.downloadQueue.clear()
        downloadJobs.clear()
        MangaServiceDataSingleton.isServiceRunning = false
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
            while (MangaServiceDataSingleton.downloadQueue.isNotEmpty()) {
                val task = MangaServiceDataSingleton.downloadQueue.poll()
                if (task != null) {
                    if (PrefManager.getVal<Boolean>(PrefName.DownloadWifiOnly) &&
                        !isWifiConnected(this@MangaDownloaderService)
                    ) {
                        broadcastDownloadFailed(task.chapter)
                        continue
                    }
                    val job = launch {
                        semaphore.withPermit {
                            try {
                                download(task)
                            } finally {
                                mutex.withLock {
                                    downloadJobs.remove(task.chapter)
                                }
                                updateNotification()
                            }
                        }
                    }
                    mutex.withLock {
                        downloadJobs[task.chapter] = job
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

    fun cancelDownload(chapter: String) {
        serviceScope.launch {
            mutex.withLock {
                downloadJobs[chapter]?.cancel()
                downloadJobs.remove(chapter)
                MangaServiceDataSingleton.downloadQueue.removeAll { it.chapter == chapter }
                updateNotification() // Update the notification after cancellation
            }
        }
    }

    private fun updateNotification() {
        // Update the notification to reflect the current state of the queue
        val pendingDownloads = MangaServiceDataSingleton.downloadQueue.size
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

    suspend fun download(task: DownloadTask) {
        try {
            withContext(Dispatchers.Main) {
                val notifi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    ContextCompat.checkSelfPermission(
                        this@MangaDownloaderService,
                        Manifest.permission.POST_NOTIFICATIONS
                    ) == PackageManager.PERMISSION_GRANTED
                } else {
                    true
                }

                val deferredMap = mutableMapOf<Int, Deferred<Bitmap?>>()
                builder.setContentText("Downloading ${task.title} - ${task.chapter}")
                if (notifi) {
                    notificationManager.notify(NOTIFICATION_ID, builder.build())
                }

                getSubDirectory(
                    this@MangaDownloaderService,
                    MediaType.MANGA,
                    false,
                    task.title,
                    task.chapter
                )?.deleteRecursively(this@MangaDownloaderService)

                // Loop through each ImageData object from the task
                var farthest = 0
                for ((index, image) in task.imageData.withIndex()) {
                    if (deferredMap.size >= task.simultaneousDownloads) {
                        deferredMap.values.awaitAll()
                        deferredMap.clear()
                    }

                    deferredMap[index] = async(Dispatchers.IO) {
                        var bitmap: Bitmap? = null
                        var retryCount = 0

                        while (bitmap == null && retryCount < task.retries) {
                            bitmap = image.fetchAndProcessImage(
                                image.page,
                                image.source
                            )
                            retryCount++
                        }

                        if (bitmap != null) {
                            saveToDisk("$index.jpg", bitmap, task.title, task.chapter)
                        }
                        farthest++
                        builder.setProgress(task.imageData.size, farthest, false)
                        broadcastDownloadProgress(
                            task.chapter,
                            farthest * 100 / task.imageData.size
                        )
                        if (notifi) {
                            notificationManager.notify(NOTIFICATION_ID, builder.build())
                        }

                        bitmap
                    }
                }

                // Wait for any remaining deferred to complete
                deferredMap.values.awaitAll()

                builder.setContentText("${task.title} - ${task.chapter} Download complete")
                    .setProgress(0, 0, false)
                notificationManager.notify(NOTIFICATION_ID, builder.build())

                saveMediaInfo(task)
                downloadsManager.addDownload(
                    DownloadedType(
                        task.title,
                        task.chapter,
                        MediaType.MANGA
                    )
                )
                broadcastDownloadFinished(task.chapter)
                snackString("${task.title} - ${task.chapter} Download finished")
            }
        } catch (e: Exception) {
            snackString("Exception while downloading file: ${e.message}")
            Logger.log(e)
            broadcastDownloadFailed(task.chapter)
        }
    }


    private fun saveToDisk(fileName: String, bitmap: Bitmap, title: String, chapter: String) {
        try {
            // Define the directory within the private external storage space
            val directory = getSubDirectory(this, MediaType.MANGA, false, title, chapter)
                ?: throw Exception("Directory not found")
            directory.findFile(fileName)?.forceDelete(this)
            // Create a file reference within that directory for the image
            val file =
                directory.createFile("image/jpeg", fileName) ?: throw Exception("File not created")

            // Use a FileOutputStream to write the bitmap to the file
            file.openOutputStream(this, false).use { outputStream ->
                if (outputStream == null) throw Exception("Output stream is null")
                bitmap.compress(Bitmap.CompressFormat.JPEG, 100, outputStream)
            }
        } catch (e: Exception) {
            snackString("Exception while saving image: ${e.message}")
            Logger.log(e)
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    private fun saveMediaInfo(task: DownloadTask) {
        launchIO {
            val directory =
                getSubDirectory(this@MangaDownloaderService, MediaType.MANGA, false, task.title)
                    ?: throw Exception("Directory not found")
            directory.findFile("media.json")?.forceDelete(this@MangaDownloaderService)
            val file = directory.createFile("application/json", "media.json")
                ?: throw Exception("File not created")
            val gson = GsonBuilder()
                .registerTypeAdapter(SChapter::class.java, InstanceCreator<SChapter> {
                    SChapterImpl() // Provide an instance of SChapterImpl
                })
                .create()
            val mediaJson = gson.toJson(task.sourceMedia)
            val media = gson.fromJson(mediaJson, Media::class.java)
            if (media != null) {
                media.cover = media.cover?.let { downloadImage(it, directory, "cover.jpg") }
                media.banner = media.banner?.let { downloadImage(it, directory, "banner.jpg") }

                val jsonString = gson.toJson(media)
                withContext(Dispatchers.Main) {
                    try {
                        file.openOutputStream(this@MangaDownloaderService, false).use { output ->
                            if (output == null) throw Exception("Output stream is null")
                            output.write(jsonString.toByteArray())
                        }
                    } catch (e: android.system.ErrnoException) {
                        Logger.log(e)
                        Toast.makeText(
                            this@MangaDownloaderService,
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
                directory.findFile(name)?.forceDelete(this@MangaDownloaderService)
                val file =
                    directory.createFile("image/jpeg", name) ?: throw Exception("File not created")
                file.openOutputStream(this@MangaDownloaderService, false).use { output ->
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
                        this@MangaDownloaderService,
                        "Exception while saving ${name}: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
                null
            } finally {
                connection?.disconnect()
            }
        }

    private fun broadcastDownloadStarted(chapterNumber: String) {
        val intent = Intent(ACTION_DOWNLOAD_STARTED).apply {
            setPackage(packageName)
            putExtra(EXTRA_CHAPTER_NUMBER, chapterNumber)
        }
        sendBroadcast(intent)
    }

    private fun broadcastDownloadFinished(chapterNumber: String) {
        val intent = Intent(ACTION_DOWNLOAD_FINISHED).apply {
            setPackage(packageName)
            putExtra(EXTRA_CHAPTER_NUMBER, chapterNumber)
        }
        sendBroadcast(intent)
    }

    private fun broadcastDownloadFailed(chapterNumber: String) {
        val intent = Intent(ACTION_DOWNLOAD_FAILED).apply {
            setPackage(packageName)
            putExtra(EXTRA_CHAPTER_NUMBER, chapterNumber)
        }
        sendBroadcast(intent)
    }

    private fun broadcastDownloadProgress(chapterNumber: String, progress: Int) {
        val intent = Intent(ACTION_DOWNLOAD_PROGRESS).apply {
            setPackage(packageName)
            putExtra(EXTRA_CHAPTER_NUMBER, chapterNumber)
            putExtra("progress", progress)
        }
        sendBroadcast(intent)
    }

    private val cancelReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_CANCEL_DOWNLOAD) {
                val chapter = intent.getStringExtra(EXTRA_CHAPTER)
                chapter?.let {
                    cancelDownload(it)
                }
            }
        }
    }


    data class DownloadTask(
        val title: String,
        val chapter: String,
        val imageData: List<ImageData>,
        val sourceMedia: Media? = null,
        val retries: Int = 2,
        val simultaneousDownloads: Int = 2,
    )

    companion object {
        private const val NOTIFICATION_ID = 1103
        const val ACTION_CANCEL_DOWNLOAD = "action_cancel_download"
        const val EXTRA_CHAPTER = "extra_chapter"
    }
}

object MangaServiceDataSingleton {
    var imageData: List<ImageData> = listOf()
    var sourceMedia: Media? = null
    var downloadQueue: Queue<MangaDownloaderService.DownloadTask> = ConcurrentLinkedQueue()

    @Volatile
    var isServiceRunning: Boolean = false
}