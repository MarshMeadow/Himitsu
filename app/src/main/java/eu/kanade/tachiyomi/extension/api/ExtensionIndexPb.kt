package eu.kanade.tachiyomi.extension.api

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream

/**
 * Minimal protobuf reader for the mihon/keiyoushi `index.pb` repo index.
 *
 * The file is a gzip-compressed protobuf message:
 *   f1  name            (string)
 *   f2  badgeLabel      (string)
 *   f3  signingKey      (string)
 *   f4  contact         (message)
 *   f101 extensionList  (message) -> f1 repeated entries
 *
 * Entry:
 *   f1 name (string), f2 pkg (string),
 *   f3 assets (message: f1 apkUrl, f2 iconUrl, f101 jarUrl)
 *   f4 libVersion (string e.g. "1.6"), f5 code (varint), f6 version (string),
 *   f7 nsfw (varint), f8 sources (repeated message: f1 id varint, f2 name,
 *   f3 lang, f4 baseUrl)
 */
object ExtensionIndexPb {

    class PbEntry(
        var name: String = "",
        var pkg: String = "",
        var apkUrl: String = "",
        var iconUrl: String = "",
        var lib: String = "",
        var code: Long = 0,
        var version: String = "",
        var nsfw: Int = 0,
        val sources: MutableList<PbSource> = mutableListOf(),
    )

    class PbSource(
        var id: Long = 0,
        var name: String = "",
        var lang: String = "",
        var baseUrl: String = "",
    )

    fun parse(gzipped: ByteArray): List<PbEntry> {
        val raw = GZIPInputStream(ByteArrayInputStream(gzipped)).use { it.readBytes() }
        val top = readFields(raw)
        for ((field, wire, value) in top) {
            if (field == 101 && wire == 2) {
                return parseEntryList(value as ByteArray)
            }
        }
        return emptyList()
    }

    private fun parseEntryList(listBytes: ByteArray): List<PbEntry> {
        return readFields(listBytes)
            .filter { it.field == 1 && it.wire == 2 }
            .map { parseEntry(it.value as ByteArray) }
    }

    private fun parseEntry(b: ByteArray): PbEntry {
        val e = PbEntry()
        for ((field, wire, value) in readFields(b)) {
            when (field) {
                1 -> e.name = str(value)
                2 -> e.pkg = str(value)
                3 -> if (wire == 2) parseAssets(value as ByteArray, e)
                4 -> e.lib = str(value)
                5 -> e.code = (value as? Long) ?: 0
                6 -> e.version = str(value)
                7 -> e.nsfw = ((value as? Long) ?: 0).toInt()
                8 -> if (wire == 2) e.sources.add(parseSource(value as ByteArray))
            }
        }
        return e
    }

    private fun parseAssets(b: ByteArray, e: PbEntry) {
        for ((field, _, value) in readFields(b)) {
            when (field) {
                1 -> e.apkUrl = str(value)
                2 -> e.iconUrl = str(value)
            }
        }
    }

    private fun parseSource(b: ByteArray): PbSource {
        val s = PbSource()
        for ((field, _, value) in readFields(b)) {
            when (field) {
                1 -> s.id = (value as? Long) ?: 0
                2 -> s.name = str(value)
                3 -> s.lang = str(value)
                4 -> s.baseUrl = str(value)
            }
        }
        return s
    }

    private data class F(val field: Int, val wire: Int, val value: Any)

    private fun str(v: Any): String = (v as? ByteArray)?.toString(Charsets.UTF_8) ?: ""

    private fun readFields(b: ByteArray): List<F> {
        val out = ArrayList<F>()
        var i = 0
        while (i < b.size) {
            val (tag, ni) = varint(b, i); i = ni
            val field = (tag shr 3).toInt()
            when (tag.toInt() and 7) {
                0 -> {
                    val (v, n2) = varint(b, i); i = n2
                    out.add(F(field, 0, v))
                }
                1 -> {
                    out.add(F(field, 1, b.copyOfRange(i, i + 8))); i += 8
                }
                2 -> {
                    val (l, n2) = varint(b, i); i = n2
                    out.add(F(field, 2, b.copyOfRange(i, i + l.toInt())))
                    i += l.toInt()
                }
                5 -> {
                    out.add(F(field, 5, b.copyOfRange(i, i + 4))); i += 4
                }
                else -> return out
            }
        }
        return out
    }

    private fun varint(b: ByteArray, start: Int): Pair<Long, Int> {
        var i = start
        var r = 0L
        var s = 0
        while (i < b.size) {
            val x = b[i].toInt(); i++
            r = r or ((x and 0x7f).toLong() shl s)
            if (x and 0x80 == 0) return r to i
            s += 7
        }
        return r to i
    }
}
