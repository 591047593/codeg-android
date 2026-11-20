package app.codeg.android.core.update

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

data class AppUpdate(
    val versionName: String,
    val versionCode: Int,
    val notes: String,
    val apkUrl: String,
    val sha256Url: String?,
    val size: Long,
    /** SHA-256 supplied by the independent HTTPS GitHub Releases API. */
    val sha256: String? = null,
)

/** Dedicated anonymous client; never inherits codeg server credentials. */
class UpdateChecker(private val client: HttpClient = HttpClient(OkHttp) {
    expectSuccess = true
    install(HttpTimeout) {
        requestTimeoutMillis = 12_000
        connectTimeoutMillis = 5_000
        socketTimeoutMillis = 8_000
    }
}) : java.io.Closeable {
    override fun close() = client.close()
    suspend fun check(currentVersion: Int, manual: Boolean = false): AppUpdate? = try {
        parse(client.get("https://api.github.com/repos/591047593/codeg-android/releases?per_page=20").bodyAsText(), currentVersion)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        if (manual) throw e else null
    }

    companion object {
        fun versionCode(tag: String): Int? = Regex("build-(\\d+)").matchEntire(tag)?.groupValues?.get(1)?.toIntOrNull()

        fun parse(body: String, currentVersion: Int): AppUpdate? {
            val release = Json.parseToJsonElement(body).jsonArray.map { it.jsonObject }
                .filter { it["draft"]?.jsonPrimitive?.booleanOrNull != true && it["prerelease"]?.jsonPrimitive?.booleanOrNull != true }
                .mapNotNull { item -> versionCode(item["tag_name"]?.jsonPrimitive?.content.orEmpty())?.let { it to item } }
                .maxByOrNull { it.first } ?: return null
            val (code, item) = release
            if (code <= currentVersion) return null
            val assets = item["assets"]?.jsonArray?.map { it.jsonObject }.orEmpty()
            val apk = assets.firstOrNull { it["name"]?.jsonPrimitive?.content?.endsWith(".apk") == true } ?: return null
            val apkName = apk["name"]!!.jsonPrimitive.content
            val checksum = assets.firstOrNull { it["name"]?.jsonPrimitive?.content == "$apkName.sha256" }
            return AppUpdate("1.1.$code", code, item["body"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                apk["browser_download_url"]!!.jsonPrimitive.content,
                checksum?.get("browser_download_url")?.jsonPrimitive?.content,
                apk["size"]?.jsonPrimitive?.longOrNull ?: 0L,
                apk["digest"]?.jsonPrimitive?.contentOrNull
                    ?.takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:")
                    ?.takeIf { it.matches(Regex("[a-fA-F0-9]{64}")) }?.lowercase())
        }
    }
}
