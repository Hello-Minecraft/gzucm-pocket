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

    /** 仓库全名。API 地址和兜底直链都从这里拼，改名只改一处 */
    private const val REPO = "Hello-Minecraft/gzucm-pocket"

    private const val API = "https://api.github.com/repos/$REPO/releases/latest"

    @kotlinx.serialization.Serializable
    data class UpdateInfo(
        val latestVersion: String,
        val notes: String = "",
        val pageUrl: String = "",
        val apkUrl: String? = null,
        val sizeMb: Double = 0.0,
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
     * 把查到的更新信息落盘。
     *
     * 静默检查和关于页手动检查都走这里，保证两处存的格式一致。
     *
     * 只有「确实有更新」时才存 —— 已是最新版本时要把旧记录清掉，
     * 否则用户升级完 App 之后还看到上一个版本的提示。
     */
    fun saveCachedInfo(store: SessionStore, info: UpdateInfo?) {
        if (info == null) {
            store.cachedUpdateInfo = null
            store.latestVersion = null
            return
        }
        store.latestVersion = info.latestVersion
        store.cachedUpdateInfo = runCatching {
            Net.json.encodeToString(UpdateInfo.serializer(), info)
        }.getOrNull()
    }

    /**
     * 读回上次查到的更新信息。没有、格式不对、或已经不再新了都返回 null。
     *
     * ## 为什么要再比一次版本号
     *
     * 用户可能在这期间自己装了新版（去 GitHub 下载、或者系统更新）。
     * 那时缓存里的「新版本」已经过期，还显示「发现 v1.3.1」会误导。
     *
     * 判定逻辑抽到 [parseCachedInfo] 里，那个是纯函数，能单测。
     */
    fun loadCachedInfo(store: SessionStore): UpdateInfo? {
        val info = parseCachedInfo(
            raw = store.cachedUpdateInfo,
            localVersion = runCatching { com.gzucm.pocket.BuildConfig.VERSION_NAME }
                .getOrDefault("0"),
        )
        if (info == null && store.cachedUpdateInfo != null) {
            // 缓存过期或损坏，顺手清掉，别一直留着脏数据
            saveCachedInfo(store, null)
        }
        return info
    }

    /**
     * 纯函数版：解析缓存的更新信息，并判断它还成不成立。
     *
     * 分开是为了能写单测 —— 上面那个版本依赖 SessionStore 和 BuildConfig。
     *
     * @return 仍然有效的更新信息；raw 为空、解析失败、或本地已经追上时返回 null
     */
    fun parseCachedInfo(raw: String?, localVersion: String): UpdateInfo? {
        if (raw.isNullOrBlank()) return null
        val info = runCatching {
            Net.json.decodeFromString(UpdateInfo.serializer(), raw)
        }.getOrNull() ?: return null
        return info.takeIf { isNewer(it.latestVersion, localVersion) }
    }

    /**
     * 浏览器要打开的地址：优先 APK 直链，没有才退回 Release 页。
     *
     * 抽成纯函数是因为这个选择决定了用户点下去是「开始下载」
     * 还是「看到一个不知道怎么操作的网页」——值得单独锁住。
     */
    fun browserUrl(info: UpdateInfo): String =
        info.apkUrl?.takeIf { it.isNotBlank() } ?: info.pageUrl

    /**
     * Release 没挂 APK 附件时的兜底直链。
     *
     * 抽成纯函数只为一件事：**附件名里带不带 `v`** 这种细节必须被测试锁住。
     * 2026-11-02 就是因为这里 `removePrefix("v")` 拼出 `gzucm-pocket-1.3.1.apk`，
     * 而真实附件叫 `gzucm-pocket-v1.3.1.apk`，GitHub 回 404 ——
     * 用户点「浏览器下载」看到一个报错页，比原来打开 Release 页还糟。
     *
     * 约定：**tag 原样拼进文件名**（tag 是 `v1.3.1`，附件就是 `gzucm-pocket-v1.3.1.apk`）。
     * 发版时要保证附件名符合这个规律，否则这里会对不上。
     */
    fun fallbackApkUrl(tag: String): String =
        "https://github.com/$REPO/releases/latest/download/gzucm-pocket-$tag.apk"

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

                /*
                 * 兜底：Release 里没挂 APK 附件时，拼一个「latest/download」直链。
                 *
                 * GitHub 会把 `releases/latest/download/<文件名>` 重定向到
                 * 最新一版的对应附件 —— 不用知道版本号，也不用先问 API。
                 * 这条路径只在附件名猜对时有效，所以只当兜底，不替代上面。
                 *
                 * **文件名里的 `v` 不能删**（2026-11-02 踩过）：
                 * tag 是 `v1.3.1`，附件名是 `gzucm-pocket-v1.3.1.apk`，
                 * 之前写成 `removePrefix("v")` → 拼出 `gzucm-pocket-1.3.1.apk`
                 * → GitHub 直接 404。真机上表现为「点浏览器下载打开一个报错页」。
                 * 顺带把大小写和 `.apk` 后缀一起对齐，附件名改动只改这一处。
                 */
                if (apkUrl.isNullOrBlank()) {
                    apkUrl = fallbackApkUrl(tag)
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
