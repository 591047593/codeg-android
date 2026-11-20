package app.codeg.android.core.update

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlin.coroutines.coroutineContext

sealed class UpdateDownloadState {
    data object Testing : UpdateDownloadState()
    data class Downloading(val route: String) : UpdateDownloadState()
    data object Verifying : UpdateDownloadState()
}

internal data class UpdateRoute(val name: String, val url: String)

/** Never send private URLs, credentials, query tokens, or arbitrary repositories to a proxy. */
internal fun githubUpdateRoutes(source: String): List<UpdateRoute> {
    val url = requireNotNull(source.toHttpUrlOrNull()) { "Invalid update URL" }
    val prefix = "/591047593/codeg-android/releases/download/"
    require(url.scheme == "https" && url.host == "github.com" && url.port == 443 &&
        url.username.isEmpty() && url.password.isEmpty() && url.encodedQuery == null && url.encodedFragment == null &&
        url.encodedPath.startsWith(prefix) &&
        url.encodedPath.removePrefix(prefix).matches(Regex("build-[0-9]+/[^/]+\\.apk"))) {
        "Only this repository's public HTTPS release APKs may be proxied"
    }
    val direct = url.toString()
    return listOf(
        UpdateRoute("GitHub", direct),
        UpdateRoute("gh-proxy.com", "https://gh-proxy.com/$direct"),
        UpdateRoute("ghfast.top", "https://ghfast.top/$direct"),
        UpdateRoute("ghproxy.net", "https://ghproxy.net/$direct"),
    )
}

/** Anonymous, bounded probes choose a measured route; only a verified full APK is returned. */
internal class UpdateDownloadEngine(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(3, TimeUnit.MINUTES).followSslRedirects(false).build(),
    private val routesFor: (String) -> List<UpdateRoute> = ::githubUpdateRoutes,
    private val probeTimeoutMillis: Long = 6000,
    private val sampleByteCount: Int = 64 * 1024,
) {
    private val probeClient = client.newBuilder()
        .callTimeout(probeTimeoutMillis, TimeUnit.MILLISECONDS).build()

    suspend fun download(
        update: AppUpdate,
        directory: File,
        onState: suspend (UpdateDownloadState) -> Unit = {},
        onProgress: suspend (Float?) -> Unit = {},
    ): File = withContext(Dispatchers.IO) {
        require(update.size >= 4) { "Missing update size" }
        val candidates = routesFor(update.apkUrl).distinctBy { it.url }.take(4)
        require(candidates.isNotEmpty()) { "No update routes configured" }
        onState(UpdateDownloadState.Testing)
        val expected = expectedDigest(update)
        val ranked = coroutineScope {
            candidates.map { route -> async { probe(route, update.size) } }
                .awaitAll().filterNotNull().sortedBy { it.second }.map { it.first }
        }
        if (ranked.isEmpty()) throw IOException("No usable update download route")
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create update cache")
        val target = File(directory, "build-${update.versionCode}.apk")
        val partial = File(directory, "build-${update.versionCode}.part")
        var lastFailure: IOException? = null
        try {
            for (route in ranked) {
                coroutineContext.ensureActive()
                onState(UpdateDownloadState.Downloading(route.name))
                onProgress(null)
                try {
                    transfer(route, update.size, partial, onProgress)
                    onState(UpdateDownloadState.Verifying)
                    val actual = partial.inputStream().use { input ->
                        val digest = MessageDigest.getInstance("SHA-256")
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            coroutineContext.ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            digest.update(buffer, 0, count)
                        }
                        digest.digest().joinToString("") { "%02x".format(it) }
                    }
                    if (!actual.equals(expected, ignoreCase = true)) throw IOException("Update SHA-256 mismatch")
                    coroutineContext.ensureActive()
                    if (target.exists() && !target.delete()) throw IOException("Cannot replace cached update")
                    if (!partial.renameTo(target)) throw IOException("Cannot finalize update")
                    return@withContext target
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IOException) {
                    coroutineContext.ensureActive()
                    lastFailure = e
                    partial.delete()
                }
            }
            throw IOException("Every measured update route failed download or verification", lastFailure)
        } finally {
            partial.delete()
        }
    }

    private suspend fun expectedDigest(update: AppUpdate): String {
        update.sha256?.let {
            if (!it.matches(Regex("[a-fA-F0-9]{64}"))) throw IOException("Invalid GitHub update digest")
            return it
        }
        // Legacy assets: never let the same untrusted proxy supply both APK and checksum.
        val checksumUrl = update.sha256Url ?: throw IOException("Update has no trusted SHA-256")
        if (checksumUrl != update.apkUrl + ".sha256") throw IOException("Invalid update checksum URL")
        githubUpdateRoutes(update.apkUrl)
        return request(probeClient, Request.Builder().url(checksumUrl).header("Accept-Encoding", "identity").build()) { response ->
            if (response.code != 200) throw IOException("Cannot obtain original GitHub checksum")
            val bytes = response.body?.byteStream()?.use { input ->
                val buffer = ByteArray(4097)
                var count = 0
                while (count < buffer.size) {
                    val read = input.read(buffer, count, buffer.size - count)
                    if (read < 0) break
                    count += read
                }
                if (count > 4096) throw IOException("Checksum file is too large")
                buffer.copyOf(count)
            } ?: throw IOException("Missing checksum body")
            val hash = bytes.toString(Charsets.UTF_8).trim().split(Regex("\\s+"))[0]
            if (!hash.matches(Regex("[a-fA-F0-9]{64}"))) throw IOException("Invalid original checksum")
            hash
        }
    }

    private suspend fun probe(route: UpdateRoute, size: Long): Pair<UpdateRoute, Long>? {
        val count = minOf(size, sampleByteCount.toLong()).toInt()
        val started = System.nanoTime()
        return try {
            request(probeClient, Request.Builder().url(route.url)
                .header("Range", "bytes=0-${count - 1}").header("Accept-Encoding", "identity").build()) { response ->
                if (response.code != 200 && response.code != 206) throw IOException("Route probe HTTP ${response.code}")
                if (response.header("Content-Type").orEmpty().contains("text/html", ignoreCase = true)) throw IOException("Route returned HTML")
                if (response.code == 206) {
                    val range = Regex("bytes 0-([0-9]+)/([0-9]+)").matchEntire(response.header("Content-Range").orEmpty())
                        ?: throw IOException("Invalid probe range")
                    if (range.groupValues[1].toLongOrNull() != (count - 1).toLong() || range.groupValues[2].toLongOrNull() != size)
                        throw IOException("Unexpected probe range")
                }
                val body = response.body ?: throw IOException("Missing route sample")
                if (response.code == 200 && body.contentLength() >= 0 && body.contentLength() != size)
                    throw IOException("Unexpected update size")
                val sample = ByteArray(count)
                body.byteStream().use { input ->
                    var read = 0
                    while (read < count) {
                        coroutineContext.ensureActive()
                        val received = input.read(sample, read, count - read)
                        if (received < 0) throw IOException("Short route sample")
                        read += received
                    }
                }
                if (sample.size < 4 || sample[0] != 0x50.toByte() || sample[1] != 0x4b.toByte() ||
                    sample[2] != 0x03.toByte() || sample[3] != 0x04.toByte()) throw IOException("Not an APK/ZIP sample")
                route to (System.nanoTime() - started)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: IOException) {
            coroutineContext.ensureActive()
            null
        }
    }

    private suspend fun transfer(route: UpdateRoute, size: Long, partial: File, progress: suspend (Float?) -> Unit) {
        request(client, Request.Builder().url(route.url).header("Accept-Encoding", "identity").build()) { response ->
            if (response.code != 200) throw IOException("Update download HTTP ${response.code}")
            val body = response.body ?: throw IOException("Missing update body")
            if (body.contentLength() >= 0 && body.contentLength() != size) throw IOException("Update length mismatch")
            body.byteStream().use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > size) throw IOException("Update exceeds expected size")
                        output.write(buffer, 0, count)
                        progress(total.toFloat() / size)
                    }
                    if (total != size) throw IOException("Incomplete update")
                }
            }
        }
    }

    /** Cancelling the owner interrupts blocking execute/read, not just the next progress tick. */
    private suspend fun <T> request(http: OkHttpClient, request: Request, block: suspend (Response) -> T): T = coroutineScope {
        val call = http.newCall(request)
        val cancellation = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { call.cancel() }
        }
        try {
            call.execute().use { block(it) }
        } finally {
            cancellation.cancel()
        }
    }
}
