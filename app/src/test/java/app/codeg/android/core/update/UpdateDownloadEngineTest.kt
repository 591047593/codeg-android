package app.codeg.android.core.update

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
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
    private lateinit var server: HttpServer
    private lateinit var directory: File
    private val executor = Executors.newCachedThreadPool()
    private val downloads = CopyOnWriteArrayList<String>()
    private val headers = CopyOnWriteArrayList<Pair<String?, String?>>()
    private val apk = ByteArray(16 * 1024) { (it % 251).toByte() }.apply {
        this[0] = 0x50; this[1] = 0x4b; this[2] = 0x03; this[3] = 0x04
    }

    @Before fun setup() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = executor
        server.start()
        directory = Files.createTempDirectory("codeg-update-test-").toFile()
    }

    @After fun cleanup() {
        server.stop(0)
        executor.shutdownNow()
        directory.deleteRecursively()
    }

    private fun route(name: String, probeDelay: Long = 0, mode: String = "ok"): UpdateRoute {
        server.createContext("/$name") { exchange ->
            try {
                headers.add(exchange.requestHeaders.getFirst("Authorization") to exchange.requestHeaders.getFirst("Cookie"))
                val probing = exchange.requestHeaders.getFirst("Range") != null
                if (probing) {
                    if (probeDelay > 0) Thread.sleep(probeDelay)
                    if (mode == "html") {
                        exchange.responseHeaders.add("Content-Type", "text/html")
                        exchange.sendResponseHeaders(200, 9)
                        exchange.responseBody.use { it.write("<html>bad".toByteArray()) }
                    } else if (mode == "ignore-range") {
                        reply(exchange, apk, 200)
                    } else {
                        val sample = apk.copyOfRange(0, 4096)
                        exchange.responseHeaders.add("Content-Range", "bytes 0-4095/${apk.size}")
                        reply(exchange, sample, 206)
                    }
                } else {
                    downloads.add(name)
                    when (mode) {
                        "fail-download" -> { exchange.sendResponseHeaders(503, -1); exchange.close() }
                        "corrupt" -> reply(exchange, apk.clone().apply { this[lastIndex] = 0 }, 200)
                        "truncated" -> reply(exchange, apk.copyOf(apk.size / 2), 200)
                        else -> reply(exchange, apk, 200)
                    }
                }
            } catch (_: IOException) { exchange.close() }
              catch (_: InterruptedException) { exchange.close(); Thread.currentThread().interrupt() }
        }
        return UpdateRoute(name, "http://127.0.0.1:${server.address.port}/$name")
    }

    private fun reply(exchange: HttpExchange, bytes: ByteArray, code: Int) {
        exchange.responseHeaders.add("Content-Type", "application/octet-stream")
        exchange.sendResponseHeaders(code, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
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
