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

    /**
     * 课表缓存的数据版本。算法改了（比如修了周次错位）就 +1，
     * 旧的缓存会被判定失效并清掉，强制重新同步。
     *
     * 为什么需要这个：周次错位的修复只改了「怎么算周」，
     * 已经缓存下来的旧数据仍然是错位的。用户升级后不重新同步的话，
     * 看到的还是错的，会以为没修好。
     *
     * 版本历史：
     *   1 = 初始
     *   2 = 修正「教务周从周日起、App 从周一起」导致的周日课错位
     */
    var sessionsDataVersion: Int
        get() = sp.getInt(KEY_SESSIONS_VERSION, 1)
        set(v) = sp.edit().putInt(KEY_SESSIONS_VERSION, v).apply()

    /** 当前代码期望的数据版本。改周次/日期算法时同步 +1。 */
    val sessionsDataVersionExpected: Int get() = 2

    /**
     * 缓存是否因算法升级而失效。
     *
     * 失效时调用方应当清掉 [cachedSessions] 并重新同步。
     */
    fun dropStaleSessionCache() {
        if (sessionsDataVersion >= sessionsDataVersionExpected) return
        sp.edit()
            .remove(KEY_CACHE_SESSIONS)
            .putInt(KEY_SESSIONS_VERSION, sessionsDataVersionExpected)
            .apply()
    }

    var gradesDataVersion: Int
        get() = sp.getInt(KEY_GRADES_VERSION, 1)
        set(v) = sp.edit().putInt(KEY_GRADES_VERSION, v).apply()

    /** 当前代码期望的成绩数据版本。解析新增字段时同步 +1。 */
    val gradesDataVersionExpected: Int get() = 2

    /**
     * 成绩缓存是否过期。
     *
     * v2 起多解析了 `kcflmc`（美育类 / 科学技术与文明类 …），老缓存里这个字段是空的，
     * 切到「公选学分」会整片「未分类」。版本不符就丢掉，让下次同步重新拉。
     */
    fun dropStaleGradeCache() {
        if (gradesDataVersion >= gradesDataVersionExpected) return
        sp.edit()
            .remove(KEY_CACHE_GRADES)
            .putInt(KEY_GRADES_VERSION, gradesDataVersionExpected)
            .apply()
    }

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

    /**
     * 课表是否显示虚线网格。
     *
     * 平板端课程会并排分列，一格挨着一格，肉眼很难判断「这块属于星期几」。
     * 画上横竖虚线（行 = 第几节，列 = 星期几）之后边界就清楚了。
     *
     * 默认关闭：手机上列本来就窄，加网格线只会显得脏。
     */
    var courseGridLines: Boolean
        get() = sp.getBoolean(KEY_GRID_LINES, false)
        set(v) = sp.edit().putBoolean(KEY_GRID_LINES, v).apply()

    /** 缓存的当月每日明细（JSON）。余额缓存了明细没缓存，导致重启后折线图和明细是空的 */
    var cachedPowerDaily: String?
        get() = sp.getString(KEY_CACHE_POWER_DAILY, null)
        set(v) = sp.edit().putString(KEY_CACHE_POWER_DAILY, v).apply()

    /** 上次静默同步电费的**尝试**时间。失败也会写，用于 30 分钟冷却 */
    var lastPowerAttemptAt: Long
        get() = sp.getLong(KEY_POWER_ATTEMPT_AT, 0L)
        set(v) = sp.edit().putLong(KEY_POWER_ATTEMPT_AT, v).apply()

    /** 上次静默同步电费的日期（yyyy-MM-dd），用来实现「每天只同步一次」 */
    var lastPowerSyncDate: String?
        get() = sp.getString(KEY_POWER_SYNC_DATE, null)
        set(v) = sp.edit().putString(KEY_POWER_SYNC_DATE, v).apply()

    /** 「登录已过期」提示过的日期。同一天只提示一次，别每次开 App 都弹 */
    var loginExpiredNotifiedDate: String?
        get() = sp.getString(KEY_LOGIN_EXPIRED_DATE, null)
        set(v) = sp.edit().putString(KEY_LOGIN_EXPIRED_DATE, v).apply()

    /** 上次静默检查更新的日期（yyyy-MM-dd），每天只查一次 */
    var lastUpdateCheckDate: String?
        get() = sp.getString(KEY_UPDATE_CHECK_DATE, null)
        set(v) = sp.edit().putString(KEY_UPDATE_CHECK_DATE, v).apply()

    /** 最近一次查到的新版本号；非空表示有更新 */
    var latestVersion: String?
        get() = sp.getString(KEY_LATEST_VERSION, null)
        set(v) = sp.edit().putString(KEY_LATEST_VERSION, v).apply()

    /** 缓存的成绩（课程 / 等级 / 体测）。之前没缓存，退出重进一旦网络抖就白屏 */
    var cachedGrades: String?
        get() = sp.getString(KEY_CACHE_GRADES, null)
        set(v) = sp.edit().putString(KEY_CACHE_GRADES, v).apply()
    var cachedLevels: String?
        get() = sp.getString(KEY_CACHE_LEVELS, null)
        set(v) = sp.edit().putString(KEY_CACHE_LEVELS, v).apply()
    var cachedFitness: String?
        get() = sp.getString(KEY_CACHE_FITNESS, null)
        set(v) = sp.edit().putString(KEY_CACHE_FITNESS, v).apply()

    /** 成绩上次成功抓取的时间 */
    var gradesCheckedAt: Long
        get() = sp.getLong(KEY_GRADES_AT, 0L)
        set(v) = sp.edit().putLong(KEY_GRADES_AT, v).apply()

    /** 上课提醒：当前没课时的提前量（分钟） */
    var classLeadFree: Int
        get() = sp.getInt(KEY_CLASS_LEAD_FREE, 20)
        set(v) = sp.edit().putInt(KEY_CLASS_LEAD_FREE, v).apply()

    /** 上课提醒：课间（上一节还没下课）的提前量（分钟） */
    var classLeadBreak: Int
        get() = sp.getInt(KEY_CLASS_LEAD_BREAK, 10)
        set(v) = sp.edit().putInt(KEY_CLASS_LEAD_BREAK, v).apply()

    /** 电费不足提醒开关，默认打开 */
    var powerAlertEnabled: Boolean
        get() = sp.getBoolean(KEY_POWER_ALERT_ON, true)
        set(v) = sp.edit().putBoolean(KEY_POWER_ALERT_ON, v).apply()

    /** 上课提醒开关，默认打开 */
    var classReminderEnabled: Boolean
        get() = sp.getBoolean(KEY_CLASS_REMIND, true)
        set(v) = sp.edit().putBoolean(KEY_CLASS_REMIND, v).apply()

    /** 默认启动页：0=今日 1=课表 2=成绩 3=电费（与 Tab 顺序一致） */
    var startTab: Int
        get() = sp.getInt(KEY_START_TAB, 0)
        set(v) = sp.edit().putInt(KEY_START_TAB, v).apply()

    /**
     * 主题色种子色（Long 形式的 ARGB）。0 = 默认薄荷。
     *
     * 存 Long 而不是 Int：SharedPreferences 只有 putLong 能无损存下
     * Color(0xFF4ADE9B) 这种带 alpha 的完整值，putInt 也能但要来回转换。
     */
    var themeSeed: Long
        get() = sp.getLong(KEY_THEME_SEED, 0L)
        set(v) = sp.edit().putLong(KEY_THEME_SEED, v).apply()

    /**
     * 时间冲突的课怎么排。
     *
     * true  = 错位叠压（默认）：两块保持接近整列宽、错开叠着，文字都读得出来
     * false = 并排分列：各占半列，字会变窄，但两块都完整可见
     */
    var overlapMode: Boolean
        get() = sp.getBoolean(KEY_OVERLAP_MODE, true)
        set(v) = sp.edit().putBoolean(KEY_OVERLAP_MODE, v).apply()

    private companion object {
        const val KEY_COOKIES = "cookies"
        const val KEY_SEMESTER = "semester"
        const val KEY_SYNC_AT = "sync_at"
        const val KEY_CACHE_SEM = "cache_semester"
        const val KEY_CACHE_SESSIONS = "cache_sessions"
        const val KEY_SESSIONS_VERSION = "sessions_data_version"
        const val KEY_CACHE_POWER = "cache_power"
        const val KEY_POWER_DAYS = "power_days"
        const val KEY_POWER_AT = "power_at"
        const val KEY_POWER_ALERT = "power_alert_date"
        const val KEY_BG_PATH = "course_bg_path"
        const val KEY_BG_ALPHA = "course_bg_alpha"
        const val KEY_GRID_LINES = "course_grid_lines"
        const val KEY_START_TAB = "start_tab"
        const val KEY_THEME_SEED = "theme_seed"
        const val KEY_OVERLAP_MODE = "overlap_mode"
        const val KEY_CLASS_REMIND = "class_remind"
        const val KEY_POWER_ALERT_ON = "power_alert_on"
        const val KEY_CLASS_LEAD_FREE = "class_lead_free"
        const val KEY_CLASS_LEAD_BREAK = "class_lead_break"
        const val KEY_UPDATE_CHECK_DATE = "update_check_date"
        const val KEY_LATEST_VERSION = "latest_version"
        const val KEY_CACHE_GRADES = "cache_grades"
        const val KEY_GRADES_VERSION = "grades_data_version"
        const val KEY_CACHE_LEVELS = "cache_levels"
        const val KEY_CACHE_FITNESS = "cache_fitness"
        const val KEY_GRADES_AT = "grades_at"
        const val KEY_LOGIN_EXPIRED_DATE = "login_expired_date"
        const val KEY_POWER_SYNC_DATE = "power_sync_date"
        const val KEY_POWER_ATTEMPT_AT = "power_attempt_at"
        const val KEY_CACHE_POWER_DAILY = "cache_power_daily"
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
            if (it.isSuccessful) {
                /**
                 * 会话过期时学校**不返 401**：302 跳到 CAS，最后 200 返回登录页 HTML。
                 * 如果原样返回，上层会拿登录页去解析，报出「数据格式不对」——
                 * 用户看到莫名其妙的话，实际只要重新登录。
                 * 所以在这里统一识别成 Unauthorized。
                 */
                if (looksLikeLoginPage(text)) throw NetError.Unauthorized(200)
                return text
            }
            if (it.code == 401 || it.code == 403) throw NetError.Unauthorized(it.code)
            throw NetError.Http(it.code, text.take(120))
        }
    }

    /**
     * 这段文本是不是统一身份认证的登录页。
     *
     * 判据：**必须是 HTML**（JSON 一律不算），且带 CAS 登录页的特征。
     * 先按首字符排除 JSON，成本为零，也不会误伤正常接口。
     */
    internal fun looksLikeLoginPage(text: String): Boolean {
        if (text.isEmpty()) return false
        val trimmed = text.trimStart()

        // ① HTML 形式的登录页（页面级请求会遇到）
        if (trimmed.startsWith("<")) {
            return trimmed.contains("统一身份认证") ||
                (trimmed.contains("authserver.gzucm.edu.cn") && trimmed.contains("execution"))
        }

        // ② JSON 形式的「没登录」（接口级请求会遇到）：
        //    {"code":-401,"message":"尚未登录，请先登录"}
        //    {"code":-401,"message":"对不起，您的帐号[x]已在别处登录，您已被迫退出。"}
        // 这类响应 HTTP 是 200，结构上也像正常的 JSON，
        // 不特意识别就会被上层当成「数据格式变了」。
        if (trimmed.startsWith("{") && trimmed.length < 4000) {
            if (trimmed.contains("\"-401\"") || trimmed.contains(":-401")) return true
            if (trimmed.contains("尚未登录") || trimmed.contains("已在别处登录")) return true
        }
        return false
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
    /** code 要暴露出去了：界面按状态码区分「登录失效」和「服务器故障」 */
    class Http(val code: Int, body: String) : NetError("服务器返回 $code：$body")
    class Parse(what: String) : NetError("数据解析失败：$what")
}
