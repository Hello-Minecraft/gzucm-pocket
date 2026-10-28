package com.gzucm.pocket.core

import android.content.Context
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** 会话持久化：cookie、学期代码、电费房间号等零碎状态。 */
class SessionStore(context: Context) {

    private val sp = context.getSharedPreferences("gzucm_pocket", Context.MODE_PRIVATE)

    fun saveCookies(host: String, cookies: Map<String, String>) {
        val all: MutableMap<String, Map<String, String>> = loadCookies().toMutableMap()
        if (cookies.isEmpty()) all.remove(host) else all[host] = cookies
        val encoded = Json.encodeToString(
            MapSerializer(String.serializer(), MapSerializer(String.serializer(), String.serializer())),
            all,
        )
        sp.edit().putString(KEY_COOKIES, encoded).apply()
    }

    fun loadCookies(): Map<String, Map<String, String>> = runCatching {
        val raw = sp.getString(KEY_COOKIES, null) ?: return emptyMap()
        Json.decodeFromString(
            MapSerializer(String.serializer(), MapSerializer(String.serializer(), String.serializer())),
            raw,
        )
    }.getOrDefault(emptyMap())

    fun clearCookies() = sp.edit().remove(KEY_COOKIES).apply()

    var semesterCode: String?
        get() = sp.getString(KEY_SEMESTER, null)
        set(v) = sp.edit().putString(KEY_SEMESTER, v).apply()

    var lastSyncAt: Long
        get() = sp.getLong(KEY_SYNC_AT, 0L)
        set(v) = sp.edit().putLong(KEY_SYNC_AT, v).apply()

    /** 课表本地缓存（json 字符串，离线可读） */
    var cachedSemester: String?
        get() = sp.getString(KEY_CACHE_SEM, null)
        set(v) = sp.edit().putString(KEY_CACHE_SEM, v).apply()

    var cachedSessions: String?
        get() = sp.getString(KEY_CACHE_SESSIONS, null)
        set(v) = sp.edit().putString(KEY_CACHE_SESSIONS, v).apply()

    var cachedPower: String?
        get() = sp.getString(KEY_CACHE_POWER, null)
        set(v) = sp.edit().putString(KEY_CACHE_POWER, v).apply()

    /**
     * 电费可用天数（余额 ÷ 近 7 天日均）。同步时算好存下来，
     * 每日巡检的 Worker 直接读，不需要联网也不需要一卡通会话。
     */
    var powerDaysLeft: Float
        get() = sp.getFloat(KEY_POWER_DAYS, -1f)
        set(v) = sp.edit().putFloat(KEY_POWER_DAYS, v).apply()

    /** 上次电费同步的时间戳，用来判断数据是不是太旧 */
    var powerCheckedAt: Long
        get() = sp.getLong(KEY_POWER_AT, 0L)
        set(v) = sp.edit().putLong(KEY_POWER_AT, v).apply()

    /** 上次发提醒的日期（yyyy-MM-dd），同一天只提醒一次 */
    var powerAlertDate: String?
        get() = sp.getString(KEY_POWER_ALERT, null)
        set(v) = sp.edit().putString(KEY_POWER_ALERT, v).apply()

    /** 课表自定义背景图的绝对路径；空表示用默认背景 */
    var courseBgPath: String?
        get() = sp.getString(KEY_BG_PATH, null)
        set(v) = sp.edit().putString(KEY_BG_PATH, v).apply()

    /** 背景不透明度 0.15~1.0 */
    var courseBgAlpha: Float
        get() = sp.getFloat(KEY_BG_ALPHA, 0.45f)
        set(v) = sp.edit().putFloat(KEY_BG_ALPHA, v).apply()

    /** 上次静默同步电费的日期（yyyy-MM-dd），用来实现「每天只同步一次」 */
    var lastPowerSyncDate: String?
        get() = sp.getString(KEY_POWER_SYNC_DATE, null)
        set(v) = sp.edit().putString(KEY_POWER_SYNC_DATE, v).apply()

    /** 默认启动页：0=今日 1=课表 2=成绩 3=电费（与 Tab 顺序一致） */
    var startTab: Int
        get() = sp.getInt(KEY_START_TAB, 0)
        set(v) = sp.edit().putInt(KEY_START_TAB, v).apply()

    private companion object {
        const val KEY_COOKIES = "cookies"
        const val KEY_SEMESTER = "semester"
        const val KEY_SYNC_AT = "sync_at"
        const val KEY_CACHE_SEM = "cache_semester"
        const val KEY_CACHE_SESSIONS = "cache_sessions"
        const val KEY_CACHE_POWER = "cache_power"
        const val KEY_POWER_DAYS = "power_days"
        const val KEY_POWER_AT = "power_at"
        const val KEY_POWER_ALERT = "power_alert_date"
        const val KEY_BG_PATH = "course_bg_path"
        const val KEY_BG_ALPHA = "course_bg_alpha"
        const val KEY_START_TAB = "start_tab"
        const val KEY_POWER_SYNC_DATE = "power_sync_date"
    }
}

/**
 * 内存 + 磁盘双层 CookieJar。
 *
 * ⚠️ 关键：**必须以 (name, path) 为键**，不能只按 name。
 * 广中医一卡通在同一个域名下有两个同名 JSESSIONID：
 *   - `Path=/user`  ← 业务接口（powerfee 等）用这个
 *   - `Path=/sso`   ← SSO 跳转用这个
 * 只按 name 存会让后者覆盖前者，导致发往 /user 下的请求带着错 Session →
 * 服务端返回登录页 HTML → 电费解析失败。这是「电费读不到」的根因。
 *
 * 发送时按 RFC 6265 的路径匹配（prefix 匹配 + 更长的 path 优先）。
 */
class PersistentCookieJar(private val store: SessionStore) : CookieJar {

    /** host -> (name|path -> value) */
    private val mem: MutableMap<String, MutableMap<String, String>> =
        store.loadCookies().mapValues { it.value.toMutableMap() }.toMutableMap()

    private fun key(name: String, path: String) = "$name|$path"

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return
        val bucket = mem.getOrPut(url.host) { mutableMapOf() }
        cookies.forEach { bucket[key(it.name, it.path)] = it.value }
        store.saveCookies(url.host, bucket)
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val bucket = mem[url.host] ?: return emptyList()
        val reqPath = url.encodedPath.ifEmpty { "/" }
        return bucket.mapNotNull { (k, v) ->
            val sep = k.lastIndexOf('|')
            if (sep <= 0) return@mapNotNull null
            val name = k.substring(0, sep)
            val path = k.substring(sep + 1).ifEmpty { "/" }
            // RFC 6265 §5.1.4 path-match
            val matches = reqPath == path ||
                (reqPath.startsWith(path) && (path.endsWith("/") || reqPath[path.length] == '/'))
            if (!matches) return@mapNotNull null
            Cookie.Builder().domain(url.host).path(path).name(name).value(v).build()
        }
    }

    /** 从 WebView 灌入。WebView 的 getCookie 丢掉了 path，这里按业务常识归位。 */
    fun seed(host: String, cookies: Map<String, String>) {
        if (cookies.isEmpty()) return
        val bucket = mem.getOrPut(host) { mutableMapOf() }
        for ((name, value) in cookies) {
            val path = if (name == "JSESSIONID" && host.contains("ecard")) {
                // 一卡通拿得到的 JSESSIONID 一定是 /user 那条（业务会话）
                "/user"
            } else {
                "/"
            }
            bucket[key(name, path)] = value
        }
        store.saveCookies(host, bucket)
    }

    /** 调试/兜底用：直接塞一条指定 path 的 cookie */
    fun put(host: String, name: String, value: String, path: String = "/") {
        val bucket = mem.getOrPut(host) { mutableMapOf() }
        bucket[key(name, path)] = value
        store.saveCookies(host, bucket)
    }

    fun snapshot(host: String): Map<String, String> = mem[host].orEmpty()

    fun has(host: String): Boolean = !mem[host].isNullOrEmpty()

    fun clear() {
        mem.clear()
        store.clearCookies()
    }
}

object Net {

    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        encodeDefaults = true
    }

    private val FORM = "application/x-www-form-urlencoded; charset=utf-8".toMediaType()

    lateinit var cookies: PersistentCookieJar
        private set

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .cookieJar(cookies)
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    fun init(context: Context) {
        cookies = PersistentCookieJar(SessionStore(context))
    }

    /** 表单 POST，返回文本。所有教务/一卡通接口统一走这里。 */
    fun postForm(url: String, params: Map<String, String>, referer: String? = null): String =
        send(Request.Builder().url(url).post(params.toFormBody()).apply {
            header("X-Requested-With", "XMLHttpRequest")
            header("User-Agent", UA)
            referer?.let { header("Referer", it) }
        }.build())

    fun get(url: String, referer: String? = null): String =
        send(Request.Builder().url(url).get().apply {
            header("User-Agent", UA)
            referer?.let { header("Referer", it) }
        }.build())

    private fun send(request: Request): String {
        val resp = try {
            client.newCall(request).execute()
        } catch (e: IOException) {
            throw NetError.Unreachable(e)
        }
        resp.use {
            val text = it.body?.string().orEmpty()
            if (it.isSuccessful) return text
            if (it.code == 401 || it.code == 403) throw NetError.Unauthorized(it.code)
            throw NetError.Http(it.code, text.take(120))
        }
    }

    const val UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    private fun Map<String, String>.toFormBody(): RequestBody {
        val sb = StringBuilder()
        for ((k, v) in this) {
            if (sb.isNotEmpty()) sb.append('&')
            sb.append(java.net.URLEncoder.encode(k, "UTF-8"))
            sb.append('=')
            sb.append(java.net.URLEncoder.encode(v, "UTF-8"))
        }
        return sb.toString().toRequestBody(FORM)
    }
}

sealed class NetError(message: String) : Exception(message) {
    class Unreachable(cause: Throwable) : NetError("网络不可达：${cause.javaClass.simpleName}")
    class Unauthorized(code: Int) : NetError("登录已失效（HTTP $code）")
    class Http(code: Int, body: String) : NetError("服务器返回 $code：$body")
    class Parse(what: String) : NetError("数据解析失败：$what")
}
