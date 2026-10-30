package com.gzucm.pocket.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 检查更新：直连 GitHub Releases。
 *
 * 为什么直连而不是自建中转：这个项目不打算维护服务器，
 * 直连的好处是「没有第三方能拿到谁在用这个 App」。
 * 代价是国内网络访问 api.github.com 可能很慢或不通 —— 所以失败要说清楚原因，
 * 不能只显示「检查失败」，否则用户分不清是没网、被墙、还是仓库删了。
 */
object Updater {

    private const val API =
        "https://api.github.com/repos/Hello-Minecraft/gzucm-pocket/releases/latest"

    data class UpdateInfo(
        val latestVersion: String,
        val notes: String,
        val pageUrl: String,
        val apkUrl: String?,
        val sizeMb: Double,
    )

    /** 单独的 client：不吃学校那边的 cookie，超时也短一些 */
    private val client by lazy {
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .build()
    }

    sealed class Result {
        data class Newer(val info: UpdateInfo) : Result()
        object UpToDate : Result()
        data class Failed(val reason: String) : Result()
    }

    /**
     * 比较版本号：只比数字段，1.2.10 > 1.2.9（按字符串比会错）。
     */
    fun isNewer(remote: String, local: String): Boolean {
        fun parts(v: String) = v.trim().removePrefix("v").removePrefix("V")
            .split("-", "+").first()
            .split(".")
            .map { it.toIntOrNull() ?: 0 }

        val a = parts(remote)
        val b = parts(local)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    suspend fun check(): Result = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(API)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "gzucm-pocket")
            .build()

        runCatching {
            client.newCall(req).execute().use { resp ->
                if (resp.code == 404) {
                    return@withContext Result.Failed("仓库或 Release 不存在（404）")
                }
                if (!resp.isSuccessful) {
                    return@withContext Result.Failed("GitHub 返回 ${resp.code}")
                }
                val body = resp.body?.string().orEmpty()
                val json = JSONObject(body)
                val tag = json.optString("tag_name").ifBlank {
                    return@withContext Result.Failed("返回里没有版本号")
                }
                val name = json.optString("name").ifBlank { tag }
                val notes = json.optString("body").ifBlank { name }
                val page = json.optString("html_url")

                // 优先找 .apk 附件
                val assets = json.optJSONArray("assets")
                var apkUrl: String? = null
                var size = 0.0
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val a = assets.optJSONObject(i) ?: continue
                        val n = a.optString("name")
                        if (n.endsWith(".apk", ignoreCase = true)) {
                            apkUrl = a.optString("browser_download_url")
                            size = a.optDouble("size", 0.0) / 1024.0 / 1024.0
                            break
                        }
                    }
                }

                val local = runCatching { com.gzucm.pocket.BuildConfig.VERSION_NAME }
                    .getOrDefault("0")
                if (isNewer(tag, local)) {
                    Result.Newer(UpdateInfo(tag.removePrefix("v"), notes, page, apkUrl, size))
                } else {
                    Result.UpToDate
                }
            }
        }.getOrElse { e ->
            // 把常见原因翻译成人话，别丢一个 IOException 给用户
            val msg = when {
                e is java.net.UnknownHostException -> "连不上 GitHub，检查网络或代理"
                e is java.net.SocketTimeoutException -> "连接 GitHub 超时"
                e is javax.net.ssl.SSLException -> "TLS 握手失败，可能被网络拦截"
                else -> "检查失败，稍后再试"
            }
            Result.Failed(msg)
        }
    }
}
