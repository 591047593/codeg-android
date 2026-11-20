package app.codeg.android.core.update

import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.ConcurrentHashMap
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class UpdateDownloadEngineTest {
    private lateinit var server: ServerSocket
    private lateinit var directory: File
    private val executor = Executors.newCachedThreadPool()
    private data class Reply(val bytes: ByteArray, val code: Int = 200, val type: String = "application/octet-stream", val range: String? = null, val pause: Long = 0)
    private val handlers = ConcurrentHashMap<String, (Map<String, String>) -> Reply>()
    private val downloads = CopyOnWriteArrayList<String>()
    private val headers = CopyOnWriteArrayList<Pair<String?, String?>>()
    private val apk = ByteArray(16 * 1024) { (it % 251).toByte() }.apply {
        this[0] = 0x50; this[1] = 0x4b; this[2] = 0x03; this[3] = 0x04
    }

    @Before fun setup() {
        server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        directory = Files.createTempDirectory("codeg-update-test-").toFile()
        executor.execute {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: IOException) { break }
                executor.execute handler@{
                    try {
                        socket.use {
                            socket.soTimeout = 3000
                            val input = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
                            val target = input.readLine()?.split(' ')?.getOrNull(1) ?: return@handler
                            val requestHeaders = mutableMapOf<String, String>()
                            while (true) {
                                val line = input.readLine() ?: break
                                if (line.isEmpty()) break
                                val separator = line.indexOf(':')
                                if (separator > 0) requestHeaders[line.substring(0, separator).lowercase()] = line.substring(separator + 1).trim()
                            }
                            val reply = handlers[target]?.invoke(requestHeaders) ?: Reply(byteArrayOf(), 404)
                            val header = "HTTP/1.1 ${reply.code} Test\r\nContent-Length: ${reply.bytes.size}\r\nContent-Type: ${reply.type}\r\nConnection: close\r\n" +
                                (reply.range?.let { "Content-Range: $it\r\n" } ?: "") + "\r\n"
                            socket.getOutputStream().apply {
                                write(header.toByteArray(Charsets.US_ASCII))
                                if (reply.pause > 0) {
                                    write(reply.bytes, 0, 4096); flush()
                                    Thread.sleep(reply.pause)
                                    write(reply.bytes, 4096, reply.bytes.size - 4096)
                                } else write(reply.bytes)
                                flush()
                            }
                        }
                    } catch (_: IOException) {} catch (_: InterruptedException) { Thread.currentThread().interrupt() }
                }
            }
        }
    }

    @After fun cleanup() {
        server.close()
        executor.shutdownNow()
        directory.deleteRecursively()
    }

    private fun route(name: String, probeDelay: Long = 0, mode: String = "ok"): UpdateRoute {
        handlers["/$name"] = { request ->
            headers.add(request["authorization"] to request["cookie"])
            if (request["range"] != null) {
                if (probeDelay > 0) Thread.sleep(probeDelay)
                when (mode) {
                    "html" -> Reply("<html>bad".toByteArray(), type = "text/html")
                    "ignore-range" -> Reply(apk)
                    else -> Reply(apk.copyOfRange(0, 4096), 206, range = "bytes 0-4095/${apk.size}")
                }
            } else {
                downloads.add(name)
                when (mode) {
                    "fail-download" -> Reply(byteArrayOf(), 503)
                    "corrupt" -> Reply(apk.clone().apply { this[lastIndex] = 0 })
                    "truncated" -> Reply(apk.copyOf(apk.size / 2))
                    "slow-download" -> Reply(apk, pause = 500)
                    else -> Reply(apk)
                }
            }
        }
        return UpdateRoute(name, "http://127.0.0.1:${server.localPort}/$name")
    }

    private fun update() = AppUpdate("1.1.200", 200, "", "https://github.com/591047593/codeg-android/releases/download/build-200/app.apk", null,
        apk.size.toLong(), sha256 = MessageDigest.getInstance("SHA-256").digest(apk).joinToString("") { "%02x".format(it) })

    private fun engine(routes: List<UpdateRoute>, timeout: Long = 1500) = UpdateDownloadEngine(
        client = OkHttpClient.Builder().connectTimeout(1, TimeUnit.SECONDS).readTimeout(2, TimeUnit.SECONDS).callTimeout(4, TimeUnit.SECONDS).build(),
        routesFor = { routes }, probeTimeoutMillis = timeout, sampleByteCount = 4096)

    @Test fun selectsFastestValidSampleRatherThanFirstConfiguredRoute() = runBlocking {
        val slow = route("slow", 350)
        val fast = route("fast", 10)
        val file = engine(listOf(slow, fast)).download(update(), directory)
        assertArrayEquals(apk, file.readBytes())
        assertEquals(listOf("fast"), downloads.toList())
        assertTrue(headers.all { it.first == null && it.second == null })
    }

    @Test fun rejectsFastHtmlAndStillUsesValidRoute() = runBlocking {
        val html = route("html", mode = "html")
        val good = route("good", 40)
        val file = engine(listOf(html, good)).download(update(), directory)
        assertArrayEquals(apk, file.readBytes())
        assertEquals(listOf("good"), downloads.toList())
    }

    @Test fun supportsServersThatIgnoreRangeWithoutRequiringWholeFileProbe() = runBlocking {
        val file = engine(listOf(route("no-range", mode = "ignore-range"))).download(update(), directory)
        assertArrayEquals(apk, file.readBytes())
    }

    @Test fun triesNextMeasuredRouteAfterFullDownloadFails() = runBlocking {
        val bad = route("bad", mode = "fail-download")
        val good = route("good", 150)
        val file = engine(listOf(bad, good)).download(update(), directory)
        assertArrayEquals(apk, file.readBytes())
        assertEquals(listOf("bad", "good"), downloads.toList())
    }

    @Test fun neverInstallsCorruptFastestPackageAndRetriesCleanly() = runBlocking {
        val bad = route("corrupt", mode = "corrupt")
        val good = route("good", 150)
        val file = engine(listOf(bad, good)).download(update(), directory)
        assertArrayEquals(apk, file.readBytes())
        assertEquals(listOf("corrupt", "good"), downloads.toList())
        assertFalse(File(directory, "build-200.part").exists())
    }

    @Test fun rejectsTruncatedPackageAndLeavesNoInstallableFile() = runBlocking {
        try {
            engine(listOf(route("truncated", mode = "truncated"))).download(update(), directory)
            fail("Truncated update must not be returned")
        } catch (_: IOException) {}
        assertEquals(emptyList<String>(), directory.list()!!.toList())
    }

    @Test fun oneHangingProbeDoesNotPreventOtherRoutesFromFinishing() = runBlocking {
        val hanging = route("hanging", 3000)
        val fast = route("fast")
        val file = withTimeout(2000) { engine(listOf(hanging, fast), 250).download(update(), directory) }
        assertArrayEquals(apk, file.readBytes())
        assertEquals(listOf("fast"), downloads.toList())
    }

    @Test fun cancellationDoesNotStartFallbackDownloads() = runBlocking {
        val job = launch(Dispatchers.IO) {
            try { engine(listOf(route("slow", 3000)), 5000).download(update(), directory) }
            catch (e: CancellationException) { throw e }
        }
        kotlinx.coroutines.delay(100)
        withTimeout(1500) { job.cancelAndJoin() }
        assertTrue(downloads.isEmpty())
        assertEquals(emptyList<String>(), directory.list()!!.toList())
    }

    @Test fun fullTransferGetsItsOwnSizeBudgetInsteadOfShortClientDeadline() = runBlocking {
        val route = route("slow-body", mode = "slow-download")
        val client = OkHttpClient.Builder().callTimeout(100, TimeUnit.MILLISECONDS).readTimeout(2, TimeUnit.SECONDS).build()
        val engine = UpdateDownloadEngine(client = client, routesFor = { listOf(route) }, probeTimeoutMillis = 1500, sampleByteCount = 4096)
        val file = engine.download(update(), directory)
        assertArrayEquals(apk, file.readBytes())
    }

    @Test fun allUnusableRoutesReportConnectivityInsteadOfGenericVerificationFailure() = runBlocking {
        try { engine(listOf(route("html", mode = "html"))).download(update(), directory); fail("No valid route") }
        catch (e: UpdateDownloadException) { assertEquals(UpdateFailureReason.NO_ROUTE, e.reason) }
        assertTrue(downloads.isEmpty())
    }

    @Test fun missingTrustedChecksumStopsBeforeContactingProxies() = runBlocking {
        try { engine(listOf(route("good"))).download(update().copy(sha256 = null), directory); fail("Missing checksum") }
        catch (e: UpdateDownloadException) { assertEquals(UpdateFailureReason.TRUSTED_CHECKSUM, e.reason) }
        assertTrue(headers.isEmpty())
    }

    @Test fun laterNetworkFailureDoesNotHideEarlierDigestMismatch() = runBlocking {
        try {
            engine(listOf(route("tampered", mode = "corrupt"), route("offline", 150, "fail-download"))).download(update(), directory)
            fail("No verified APK")
        } catch (e: UpdateDownloadException) { assertEquals(UpdateFailureReason.VERIFICATION, e.reason) }
        assertEquals(listOf("tampered", "offline"), downloads.toList())
        assertEquals(emptyList<String>(), directory.list()!!.toList())
    }

    @Test fun cancellationDuringBodyReadCleansHalfFileWithoutStartingAnotherRoute() = runBlocking {
        val first = route("slow-body", mode = "slow-download")
        val second = route("backup", 50)
        val job = launch(Dispatchers.IO) { engine(listOf(first, second)).download(update(), directory) }
        withTimeout(2000) { while (downloads.isEmpty()) kotlinx.coroutines.delay(10) }
        withTimeout(1500) { job.cancelAndJoin() }
        assertEquals(listOf("slow-body"), downloads.toList())
        assertEquals(emptyList<String>(), directory.list()!!.toList())
    }

    @Test fun routeFactoryOnlyProxiesThisReposPublicHttpsReleaseApks() {
        val routes = githubUpdateRoutes(update().apkUrl)
        assertEquals(4, routes.size)
        assertTrue(routes.any { it.url == update().apkUrl })
        for (url in listOf("http://github.com/591047593/codeg-android/releases/download/build-200/app.apk",
            "https://github.com/other/private/releases/download/build-1/app.apk",
            "https://user:test-password@github.com/591047593/codeg-android/releases/download/build-200/app.apk",
            update().apkUrl + "?token=secret")) {
            try { githubUpdateRoutes(url); fail("Unsafe proxy target accepted: $url") }
            catch (_: IllegalArgumentException) {}
        }
    }
}
