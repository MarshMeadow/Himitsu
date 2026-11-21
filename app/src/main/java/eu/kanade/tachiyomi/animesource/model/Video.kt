package eu.kanade.tachiyomi.animesource.model

import android.net.Uri
import eu.kanade.tachiyomi.network.ProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonObject
import okhttp3.Headers
import rx.subjects.Subject
import java.io.IOException
import kotlin.jvm.internal.DefaultConstructorMarker
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.Serializable

data class Track(val url: String, val lang: String) : Serializable

enum class ChapterType {
    Opening,
    Ending,
    Recap,
    MixedOp,
    Other,
}

data class TimeStamp(
    val start: Double,
    val end: Double,
    val name: String,
    val type: ChapterType = ChapterType.Other,
) : Serializable

open class Video(
    val url: String = "",
    val quality: String = "",
    var videoUrl: String? = null,
    headers: Headers? = null,
    // "url", "language-label-2", "url2", "language-label-2"
    val subtitleTracks: List<Track> = emptyList(),
    val audioTracks: List<Track> = emptyList(),
    val videoTitle: String = "",
) : Serializable, ProgressListener {

    @Transient
    var headers: Headers? = headers

    @Suppress("UNUSED_PARAMETER")
    constructor(
        url: String,
        quality: String,
        videoUrl: String?,
        uri: Uri? = null,
        headers: Headers? = null,
    ) : this(url, quality, videoUrl, headers)

    constructor(
        url: String,
        quality: String,
        videoUrl: String?,
        headers: Headers?,
        subtitleTracks: List<Track>,
        audioTracks: List<Track>,
    ) : this(url, quality, videoUrl, headers, subtitleTracks, audioTracks, "")

    // ABI shim: ext-lib <16 extensions link against the synthetic default-args
    // constructor Video(String,String,String,Headers,List,List,int,DefaultConstructorMarker).
    // Kotlin only emits that overload when the constructor declares default values, so the
    // marker signature is declared explicitly. Masked (null) params fall back to the
    // original defaults, matching the real synthetic's behaviour.
    @Suppress("UNUSED_PARAMETER")
    constructor(
        url: String?,
        quality: String?,
        videoUrl: String?,
        headers: Headers?,
        subtitleTracks: List<Track>?,
        audioTracks: List<Track>?,
        mask: Int,
        marker: DefaultConstructorMarker?,
    ) : this(
        url ?: "",
        quality ?: "",
        videoUrl,
        headers,
        subtitleTracks.orEmpty(),
        audioTracks.orEmpty(),
        "",
    )

    // ext-lib 16+ fields (ABI compat for extensions built against newer aniyomi-lib)
    var resolution: Int? = null
    var bitrate: Int? = null
    var preferred: Boolean = false
    var timestamps: List<TimeStamp> = emptyList()
    var mpvArgs: List<Pair<String, String>> = emptyList()
    var ffmpegStreamArgs: List<Pair<String, String>> = emptyList()
    var ffmpegVideoArgs: List<Pair<String, String>> = emptyList()
    var internalData: String = ""
    var initialized: Boolean = false

    // Ext lib 16 constructor (ABI compat — extensions built against newer aniyomi-lib
    // invoke this signature through the synthetic default-args overload)
    constructor(
        videoUrl: String = "",
        videoTitle: String = "",
        resolution: Int? = null,
        bitrate: Int? = null,
        headers: Headers? = null,
        preferred: Boolean = false,
        subtitleTracks: List<Track> = emptyList(),
        audioTracks: List<Track> = emptyList(),
        timestamps: List<TimeStamp> = emptyList(),
        mpvArgs: List<Pair<String, String>> = emptyList(),
        ffmpegStreamArgs: List<Pair<String, String>> = emptyList(),
        ffmpegVideoArgs: List<Pair<String, String>> = emptyList(),
        internalData: String = "",
        initialized: Boolean = false,
    ) : this(videoUrl, videoTitle, videoUrl, headers, subtitleTracks, audioTracks, videoTitle) {
        this.resolution = resolution
        this.bitrate = bitrate
        this.preferred = preferred
        this.timestamps = timestamps
        this.mpvArgs = mpvArgs
        this.ffmpegStreamArgs = ffmpegStreamArgs
        this.ffmpegVideoArgs = ffmpegVideoArgs
        this.internalData = internalData
        this.initialized = initialized
    }

    @Transient
    val memo: JsonObject = JsonObject(emptyMap())

    @Transient
    @Volatile
    var status: State = State.QUEUE

    @Transient
    private val _progressFlow = MutableStateFlow(0)

    @Transient
    val progressFlow = _progressFlow.asStateFlow()
    var progress: Int
        get() = _progressFlow.value
        set(value) {
            _progressFlow.value = value
        }

    @Transient
    @Volatile
    var totalBytesDownloaded: Long = 0L

    @Transient
    @Volatile
    var totalContentLength: Long = 0L

    @Transient
    @Volatile
    var bytesDownloaded: Long = 0L
        set(value) {
            totalBytesDownloaded += if (value < field) {
                value
            } else {
                value - field
            }
            field = value
        }

    @Transient
    var progressSubject: Subject<State, State>? = null

    override fun update(bytesRead: Long, contentLength: Long, done: Boolean) {
        bytesDownloaded = bytesRead
        if (contentLength > totalContentLength) {
            totalContentLength = contentLength
        }
        val newProgress = if (totalContentLength > 0) {
            (100 * totalBytesDownloaded / totalContentLength).toInt()
        } else {
            -1
        }
        if (progress != newProgress) progress = newProgress
    }

    enum class State {
        QUEUE,
        LOAD_VIDEO,
        DOWNLOAD_IMAGE,
        READY,
        ERROR,
    }

    @Throws(IOException::class)
    private fun writeObject(out: ObjectOutputStream) {
        out.defaultWriteObject()
        val headersMap: Map<String, List<String>> = headers?.toMultimap() ?: emptyMap()
        out.writeObject(headersMap)
    }

    @Suppress("UNCHECKED_CAST")
    @Throws(IOException::class, ClassNotFoundException::class)
    private fun readObject(input: ObjectInputStream) {
        input.defaultReadObject()
        val headersMap = input.readObject() as? Map<String, List<String>>
        headers = headersMap?.let { map ->
            val builder = Headers.Builder()
            for ((key, values) in map) {
                for (value in values) {
                    builder.add(key, value)
                }
            }
            builder.build()
        }

    }
}
