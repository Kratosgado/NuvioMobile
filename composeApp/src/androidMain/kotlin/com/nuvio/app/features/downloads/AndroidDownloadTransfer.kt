package com.nuvio.app.features.downloads

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request

internal val downloadHttpClient = OkHttpClient.Builder()
    .connectTimeout(60, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS)
    .writeTimeout(60, TimeUnit.SECONDS)
    .followRedirects(true)
    .followSslRedirects(true)
    .build()

internal class DownloadHttpException(val statusCode: Int) : IOException("Download failed: HTTP $statusCode")

internal suspend fun transferAndroidDownload(
    item: DownloadItem,
    directory: File,
    validator: String?,
    client: OkHttpClient = downloadHttpClient,
    onHeaders: (totalBytes: Long?, validator: String?) -> Unit,
    onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
): File = coroutineScope {
    val activeCall = AtomicReference<Call?>()
    val transfer = async(Dispatchers.IO) {
        try {
            require(File(item.fileName).name == item.fileName && item.fileName.isNotBlank())
            check(directory.isDirectory || directory.mkdirs()) { "Cannot create downloads directory" }
            val partial = File(directory, "${item.fileName}.part")
            var offset = partial.takeIf(File::isFile)?.length() ?: 0L
            var restarted = false

            while (true) {
                ensureActive()
                val request = Request.Builder().url(item.sourceUrl).apply {
                    item.sourceHeaders.forEach { (key, value) ->
                        if (!key.equals("Range", true) && !key.equals("If-Range", true) &&
                            !key.equals("Accept-Encoding", true)) header(key, value)
                    }
                    header("Accept-Encoding", "identity")
                    if (offset > 0L) {
                        header("Range", "bytes=$offset-")
                        validator?.let { header("If-Range", it) }
                    }
                }.build()
                val call = client.newCall(request)
                activeCall.set(call)
                ensureActive()
                call.execute().use { response ->
                    if (response.code == 416 && offset > 0L && !restarted) {
                        offset = 0L
                        restarted = true
                        return@use
                    }
                    if (!response.isSuccessful) throw DownloadHttpException(response.code)
                    val range = response.header("Content-Range")?.let(::parseDownloadContentRange)
                    val resumed = response.code == 206
                    if (resumed && (range == null || range.first != offset)) {
                        throw IOException("Server returned an invalid download range")
                    }
                    val startingBytes = if (resumed) offset else 0L
                    val body = response.body ?: throw IOException("Download response is empty")
                    val contentLength = body.contentLength().takeIf { it >= 0L }
                    val totalBytes = range?.second ?: contentLength?.let { startingBytes + it }
                    val responseValidator = response.header("ETag")?.takeUnless { it.startsWith("W/") }
                        ?: response.header("Last-Modified")
                    onHeaders(totalBytes, responseValidator)
                    var downloaded = startingBytes
                    onProgress(downloaded, totalBytes)
                    body.byteStream().use { input ->
                        FileOutputStream(partial, resumed && offset > 0L).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                ensureActive()
                                val size = input.read(buffer)
                                if (size == -1) break
                                output.write(buffer, 0, size)
                                downloaded += size
                                onProgress(downloaded, totalBytes)
                            }
                            output.fd.sync()
                        }
                    }
                    ensureActive()
                    if (totalBytes != null && downloaded != totalBytes) {
                        throw IOException("Download ended before all bytes were received")
                    }
                    return@async partial
                }
            }
            @Suppress("UNREACHABLE_CODE")
            partial
        } catch (error: Exception) {
            // Closing a cancelled HTTP call throws IOException from its blocking read.
            ensureActive()
            throw error
        }
    }
    try {
        transfer.await()
    } finally {
        activeCall.get()?.cancel()
    }
}

internal fun parseDownloadContentRange(value: String): Pair<Long, Long?>? {
    val match = Regex("bytes (\\d+)-(\\d+)/(\\d+|\\*)").matchEntire(value.trim()) ?: return null
    val start = match.groupValues[1].toLongOrNull() ?: return null
    val end = match.groupValues[2].toLongOrNull() ?: return null
    val total = match.groupValues[3].toLongOrNull()
    if (end < start || (total != null && end >= total)) return null
    return start to total
}

/**
 * Downloads an HLS stream by fetching [DownloadItem.hlsSegmentUrls] in order and concatenating them
 * into a single `.part` file, then returns that file for the caller to finalize.
 *
 * Segments are fetched sequentially and appended. There is no per-segment resume: a paused/retried
 * HLS download restarts from the first segment (the `.part` is truncated), trading some bandwidth
 * for simplicity and correctness. Progress is reported as a byte count with an estimated total
 * derived from the average segment size so the UI shows a moving, bounded bar.
 */
internal suspend fun transferAndroidHlsDownload(
    item: DownloadItem,
    directory: File,
    client: OkHttpClient = downloadHttpClient,
    onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
): File = coroutineScope {
    val segmentUrls = item.hlsSegmentUrls.orEmpty()
    require(segmentUrls.isNotEmpty()) { "HLS download has no segments" }
    require(File(item.fileName).name == item.fileName && item.fileName.isNotBlank())
    check(directory.isDirectory || directory.mkdirs()) { "Cannot create downloads directory" }

    val activeCall = AtomicReference<Call?>()
    val transfer = async(Dispatchers.IO) {
        try {
            val partial = File(directory, "${item.fileName}.part")
            // Fresh start each run: segment concatenation cannot resume mid-stream reliably.
            if (partial.exists()) partial.delete()

            var downloaded = 0L
            val segmentCount = segmentUrls.size
            onProgress(0L, null)

            FileOutputStream(partial, false).use { output ->
                segmentUrls.forEachIndexed { index, segmentUrl ->
                    ensureActive()
                    val request = Request.Builder().url(segmentUrl).apply {
                        item.sourceHeaders.forEach { (key, value) ->
                            if (!key.equals("Range", true) && !key.equals("If-Range", true) &&
                                !key.equals("Accept-Encoding", true)
                            ) {
                                header(key, value)
                            }
                        }
                        header("Accept-Encoding", "identity")
                    }.build()
                    val call = client.newCall(request)
                    activeCall.set(call)
                    ensureActive()
                    call.execute().use { response ->
                        if (!response.isSuccessful) throw DownloadHttpException(response.code)
                        val body = response.body ?: throw IOException("Segment response is empty")
                        body.byteStream().use { input ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                ensureActive()
                                val size = input.read(buffer)
                                if (size == -1) break
                                output.write(buffer, 0, size)
                                downloaded += size
                                // Estimate total from the running average segment size.
                                val segmentsDone = index // segments fully written before this one
                                val estimatedTotal = if (segmentsDone > 0) {
                                    downloaded / segmentsDone * segmentCount
                                } else {
                                    null
                                }
                                onProgress(downloaded, estimatedTotal?.takeIf { it >= downloaded })
                            }
                        }
                    }
                    // Report a segment-boundary progress tick with a sharper total estimate.
                    val completedSegments = index + 1
                    val estimatedTotal = downloaded / completedSegments * segmentCount
                    onProgress(downloaded, estimatedTotal.takeIf { it >= downloaded })
                }
                output.fd.sync()
            }

            ensureActive()
            // Final: the true total is now known.
            onProgress(downloaded, downloaded)
            partial
        } catch (error: Exception) {
            ensureActive()
            throw error
        }
    }
    try {
        transfer.await()
    } finally {
        activeCall.get()?.cancel()
    }
}
