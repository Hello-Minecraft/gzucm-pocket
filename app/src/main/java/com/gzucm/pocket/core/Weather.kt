package com.gzucm.pocket.core

import android.content.Context
import com.gzucm.pocket.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Request
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 * 天气：和风天气（QWeather）实时 + 未来几天预报。
 *
 * ## 为什么挂一个第三方
 *
 * 这个 App 一直声称「数据直连学校系统，不经过任何第三方服务器」。
 * 天气是唯一做不到的一条 —— 学校不提供，自己也没有服务器。
 * 所以这里只做一件事：把请求压到最少（每小时最多一次），
 * 并且把它写在隐私说明里，不假装不存在。
 *
 * ## 为什么 key 不进仓库
 *
 * 仓库是公开的。和风的免费 key 一旦提交上去，等于公开的额度 ——
 * 爬虫几分钟就能把它用光，而且他本人还要为此负责。
 * 所以 key 从根目录 `weather.properties`（gitignore）读进 BuildConfig，
 * 别人 clone 下来构建只会得到一个**没有天气卡**的版本，其余功能不受影响。
 *
 * ## 1 小时节流的理由
 *
 * 和风免费版按天算额度，一小时一次的频率对他自己完全够用；
 * 而 App 常常被切到后台又切回来，不做节流的话每次 ON_RESUME 都打一发，
 * 一天几十次，额度消耗和电量都不值得。
 */
object Weather {

    /** 两次请求之间的最小间隔。致辉 2026-11-20 定：**最多 1 次/小时**。 */
    const val MinIntervalMs: Long = 60L * 60L * 1000L

    /**
     * 卡片标题上的默认地点名。
     *
     * 以前这里是写死的常量「广州 · 三元里」。现在地点由用户在设置里选
     * （见 [WeatherPlace]），卡片显示的是 [Info.city] —— 拉取时按当时选中的
     * place 写进去的。这个属性只是把「默认地点叫什么」留给不关心地点的调用方
     * （主要是不联网的截图测试），**不要拿它决定界面显示的内容**。
     */
    val CityLabel: String get() = WeatherPlace.default.label

    /**
     * 天气功能的开关。
     *
     * 两种接法二选一：
     *  · **转发**（推荐）：`WEATHER_BASE` 指向自己的云函数，App 只认这一个域名；
     *  · **直连**（临时/自用）：`WEATHER_KEY` 是 API Key，JWT 模式走不通这条路。
     *
     * 两个都没配时整块消失，不留半个残废的卡。
     */
    val enabled: Boolean
        get() = BuildConfig.WEATHER_BASE.isNotBlank() || BuildConfig.WEATHER_KEY.isNotBlank()

    private val json = Json { ignoreUnknownKeys = true }

    private val client by lazy {
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    // ── 数据 ──────────────────────────────────────────────────────

    @Serializable
    data class Day(
        /** `2026-11-21` */
        val date: String,
        val text: String,
        /** 和风的天气现象代码，如 `101`。UI 用 [Weather.kindOf] 映射成图形 */
        val icon: String,
        val max: Int,
        val min: Int,
        /** 降水量 mm。和风免费版 7 天预报没有「降水概率」，只有量 */
        val precip: Double = 0.0,
        val humidity: Int = 0,
    )

    @Serializable
    data class Info(
        val city: String,
        val temp: Int,
        val feelsLike: Int,
        val humidity: Int,
        val text: String,
        val icon: String,
        val days: List<Day> = emptyList(),
        /** 拉取时刻（毫秒）。节流和「更新于」都靠它 */
        val fetchedAt: Long,
    ) {
        val kind: Kind get() = kindOf(icon)
    }

    enum class Kind(val label: String) {
        Sunny("晴"),
        Cloudy("多云"),
        Overcast("阴"),
        Rain("雨"),
        Storm("雷阵雨"),
        Snow("雪"),
        Fog("雾霾"),
        Unknown("—"),
    }

    // ── 纯函数：解析与判定（都能在本机单测里跑） ──────────────────

    @Serializable
    private data class Resp(
        val code: String = "",
        val now: NowDto? = null,
        val daily: List<DayDto> = emptyList(),
    )

    @Serializable
    private data class NowDto(
        val temp: String = "",
        val feelsLike: String = "",
        val humidity: String = "",
        val text: String = "",
        val icon: String = "",
    )

    @Serializable
    private data class DayDto(
        val fxDate: String = "",
        val tempMax: String = "",
        val tempMin: String = "",
        val textDay: String = "",
        val iconDay: String = "",
        val precip: String = "",
        val humidity: String = "",
    )

    /** 实时天气。失败（含 `code != 200`）返回 null，调用方保留旧数据 */
    fun parseNow(raw: String, fetchedAt: Long, city: String = CityLabel): Info? = runCatching {
        val r = json.decodeFromString<Resp>(raw)
        if (r.code != "200") return null
        val n = r.now ?: return null
        Info(
            city = city,
            temp = n.temp.toIntOrNull() ?: return null,
            feelsLike = n.feelsLike.toIntOrNull() ?: n.temp.toIntOrNull() ?: 0,
            humidity = n.humidity.toIntOrNull() ?: 0,
            text = n.text.ifBlank { kindOf(n.icon).label },
            icon = n.icon,
            fetchedAt = fetchedAt,
        )
    }.getOrNull()

    /** 未来几天的预报 */
    fun parseDays(raw: String): List<Day> = runCatching {
        val r = json.decodeFromString<Resp>(raw)
        if (r.code != "200") return emptyList()
        r.daily.mapNotNull { d ->
            val max = d.tempMax.toIntOrNull() ?: return@mapNotNull null
            val min = d.tempMin.toIntOrNull() ?: return@mapNotNull null
            Day(
                date = d.fxDate,
                text = d.textDay.ifBlank { kindOf(d.iconDay).label },
                icon = d.iconDay,
                max = max,
                min = min,
                precip = d.precip.toDoubleOrNull() ?: 0.0,
                humidity = d.humidity.toIntOrNull() ?: 0,
            )
        }
    }.getOrDefault(emptyList())

    fun withDays(info: Info, days: List<Day>): Info = info.copy(days = days)

    /**
     * 天气现象代码 → 图形种类。
     *
     * 和风的码是分段的：1xx 晴/多云/阴，3xx 雨，4xx 雪，5xx 雾霾沙尘。
     * 这里只需要「画哪种图标」，不需要精确到「小雨 / 中雨」——文字部分用接口给的原文。
     */
    fun kindOf(icon: String): Kind {
        val c = icon.toIntOrNull() ?: return Kind.Unknown
        return when {
            c == 100 || c == 150 || c == 900 -> Kind.Sunny
            c in 101..103 || c in 151..153 || c == 901 -> Kind.Cloudy
            c == 104 -> Kind.Overcast
            // 雷阵雨要排在 3xx 之前判断，否则会被当成普通雨
            c in 302..304 -> Kind.Storm
            c in 300..399 -> Kind.Rain
            c in 400..499 -> Kind.Snow
            c in 500..515 -> Kind.Fog
            else -> Kind.Unknown
        }
    }

    /** 到了两次请求的最小间隔没有 */
    fun canRefresh(lastAt: Long, now: Long): Boolean = now - lastAt >= MinIntervalMs

    /** 距下次可以刷新还有多久（毫秒，已到期返回 0） */
    fun waitMs(lastAt: Long, now: Long): Long = (MinIntervalMs - (now - lastAt)).coerceAtLeast(0L)

    /** 缓存若太旧（超过 6 小时）就不显示，宁可空着也不摆一个昨天的温度 */
    fun isFresh(lastAt: Long, now: Long): Boolean = now - lastAt <= 6L * 60L * 60L * 1000L

    // ── 网络 ──────────────────────────────────────────────────────

    /**
     * 最近一次失败的原因。
     *
     * 只有配 key 的那个人需要它 —— 用户看到「天气暂时取不到」就够了，
     * 但自己排查时要分得清「没网 / key 错 / 套餐到期」。
     * 写在这里而不是塞进 UI：UI 只需要一句话，日志和 Toast 才需要细节。
     */
    @Volatile
    var lastError: String? = null
        private set

    private class HttpStatus(val code: Int) : Exception("HTTP $code")

    private fun base(): String {
        val host = BuildConfig.WEATHER_HOST.ifBlank { "https://devapi.qweather.com" }
        return host.trimEnd('/')
    }

    private fun getOrThrow(url: String, withAppToken: Boolean = false): String {
        val req = Request.Builder().url(url).get().apply {
            // 只在请求自己的云函数时带；发给和风的多余头没必要
            if (withAppToken && BuildConfig.WEATHER_APP_TOKEN.isNotBlank()) {
                header("X-App-Token", BuildConfig.WEATHER_APP_TOKEN)
            }
        }.build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw HttpStatus(resp.code)
            return resp.body?.string() ?: throw IllegalStateException("响应为空")
        }
    }

    /** 从失败响应里抠出和风的业务码，便于区分 401（key 无效）与 402（超额） */
    private fun apiCodeOf(raw: String): String? =
        Regex("\"code\"\\s*:\\s*\"(\\d+)\"").find(raw)?.groupValues?.get(1)

    /**
     * 真去拉一次（不问节流）。调用方负责判断 [canRefresh]。
     *
     * 两次请求（实时 + 7 天）分开做：实时失败就整条失败，
     * 预报失败只影响下方那排小字 —— 手里至少还有「现在多少度」。
     *
     * [place] 决定这次看哪儿。默认值保证老调用点（和不关心地点的测试）
     * 行为不变：还是三元里。
     */
    suspend fun fetch(place: WeatherPlace = WeatherPlace.default): Info? =
        withContext(Dispatchers.IO) {
            if (BuildConfig.WEATHER_BASE.isNotBlank()) fetchViaProxy(place) else fetchDirect(place)
        }

    /**
     * 坐标统一保留两位小数。
     *
     * 用 [Locale.US] 而不是默认 locale：德语/法语环境下 `String.format("%.2f")`
     * 会输出 `23,16`，拼进 query 就是两个参数了。
     */
    private fun coord(v: Double): String = String.format(java.util.Locale.US, "%.2f", v)

    /**
     * 走自己的云函数（推荐）。
     *
     * 函数负责签 JWT、缓存、限流；App 这边只知道两个路径：
     * `/weather/now` 和 `/weather/7d`，地点用 `lat`/`lon` 两个 query 参数给。
     * 不传时服务端回落到默认城市（广州），所以老版本 App 也不会坏。
     */
    private fun fetchViaProxy(place: WeatherPlace): Info? {
        val base = BuildConfig.WEATHER_BASE.trimEnd('/')
        val query = "lat=${coord(place.lat)}&lon=${coord(place.lon)}"
        return try {
            val nowRaw = getOrThrow("$base/weather/now?$query", withAppToken = true)
            val now = parseNow(nowRaw, System.currentTimeMillis(), place.label)
            if (now == null) {
                lastError = "天气服务返回异常（${apiCodeOf(nowRaw) ?: "无 code"}）"
                return null
            }
            val daysRaw = runCatching {
                getOrThrow("$base/weather/7d?$query", withAppToken = true)
            }.getOrNull().orEmpty()
            lastError = null
            withDays(now, parseDays(daysRaw))
        } catch (e: HttpStatus) {
            lastError = when (e.code) {
                403 -> "天气服务拒绝访问（App Token 不一致）"
                429 -> "天气请求太频繁，稍后再试"
                502 -> "天气服务连不上和风"
                else -> "天气转发失败（HTTP ${e.code}）"
            }
            null
        } catch (e: Exception) {
            lastError = "天气转发失败（网络不通）"
            null
        }
    }

    /**
     * 直连和风（用 API Key）。
     *
     * 保留这条路只为了「自己一个人用、懒得部署函数」的场景。
     * **JWT 模式走不通这里** —— JWT 需要 Ed25519 私钥现签，私钥不能进 APK。
     *
     * 和风的位置参数是 `location=经度,纬度` —— **经度在前**，和别家相反。
     * 写反了不会报错，只会静默返回地球上另一个角落的天气（南海里），
     * 所以这里把 lon 放在前面这件事有注释钉着。
     */
    private suspend fun fetchDirect(place: WeatherPlace): Info? = withContext(Dispatchers.IO) {

        val key = BuildConfig.WEATHER_KEY
        if (key.isBlank()) {
            lastError = "未配置天气密钥"
            return@withContext null
        }
        val loc = "${coord(place.lon)},${coord(place.lat)}"
        try {
            val nowRaw = getOrThrow("${base()}/v7/weather/now?location=$loc&key=$key")
            val now = parseNow(nowRaw, System.currentTimeMillis(), place.label)
            if (now == null) {
                lastError = apiCodeOf(nowRaw)?.let { code ->
                    if (code == "401" || code == "403") "天气服务未授权（key 或 host 不对）"
                    else "天气接口返回 $code"
                } ?: "实时天气解析失败"
                return@withContext null
            }
            val daysRaw = runCatching {
                getOrThrow("${base()}/v7/weather/7d?location=$loc&key=$key")
            }.getOrNull().orEmpty()
            lastError = null
            withDays(now, parseDays(daysRaw))
        } catch (e: HttpStatus) {
            lastError = when (e.code) {
                401, 403 -> "天气服务未授权（key 或 host 不对）"
                402 -> "天气接口额度用完"
                else -> "天气请求失败（HTTP ${e.code}）"
            }
            null
        } catch (e: Exception) {
            lastError = "天气请求失败（网络不通）"
            null
        }
    }

    // ── 缓存 ──────────────────────────────────────────────────────

    fun cached(store: SessionStore): Info? {
        val raw = store.weatherCache ?: return null
        val info = runCatching { json.decodeFromString<Info>(raw) }.getOrNull() ?: return null
        // 太旧的不给（但留着，下次刷新会覆盖）
        return if (isFresh(info.fetchedAt, System.currentTimeMillis())) info else null
    }

    fun save(ctx: Context, store: SessionStore, info: Info) {
        store.weatherCache = runCatching { json.encodeToString(info) }.getOrNull()
        /*
         * 顺手记下「今天」的最高/最低温。
         *
         * 和风的 3d 预报只有今天/明天/后天，没有昨天；而天气提醒要讲
         * 「今天比昨天冷几度」。所以每次成功拉取都把今天这一条落盘，
         * 明天早上读昨天那一笔就有了基准。
         *
         * 放在 save 里而不是各个调用点：只要拉到了就一定会存，
         * 少一处调用就少一条「明天没有昨天数据」。
         */
        WeatherSnapshot.record(ctx, info.days)
    }
}

/**
 * 每日最高/最低温快照（按日期一条，只留最近 7 天）。
 *
 * 独立一个 prefs 文件，不和会话/课表混在一起 —— 它只是一份「历史温度」，
 * 丢了最多让某天的温差判断退化成「只讲高温/低温/有雨」，不影响任何主流程。
 */
object WeatherSnapshot {

    private const val PREFS = "weather_snap"

    /** 需求里指定的 key 前缀：`wx_snap_2026-11-26` */
    private const val PREFIX = "wx_snap_"

    /** 只留 7 天：再早的没人看，留着只是让 prefs 慢慢长大 */
    const val KEEP_DAYS = 7

    data class Snap(val max: Int, val min: Int)

    /** 从一次成功的拉取里记下今天；预报里找不到今天（跨天/接口异常）就什么都不做 */
    fun record(ctx: Context, days: List<Weather.Day>, today: LocalDate = LocalDate.now()) {
        val iso = today.toString()
        val d = days.firstOrNull { it.date == iso } ?: return
        prefs(ctx).edit().putString(PREFIX + iso, "${d.max},${d.min}").apply()
        prune(ctx, today)
    }

    /** 读某一天的快照；没有或存坏了返回 null（调用方据此跳过温差判断） */
    fun of(ctx: Context, date: LocalDate): Snap? {
        val raw = prefs(ctx).getString(PREFIX + date, null) ?: return null
        val parts = raw.split(',')
        if (parts.size != 2) return null
        val max = parts[0].trim().toIntOrNull() ?: return null
        val min = parts[1].trim().toIntOrNull() ?: return null
        return Snap(max, min)
    }

    fun prune(ctx: Context, today: LocalDate = LocalDate.now()) {
        val sp = prefs(ctx)
        val dead = expiredKeys(sp.all.keys.filter { it.startsWith(PREFIX) }, today)
        if (dead.isEmpty()) return
        val e = sp.edit()
        dead.forEach { e.remove(it) }
        e.apply()
    }

    /**
     * 哪些 key 该删。抽成纯函数方便单测 —— 保留窗口算错会悄悄删掉「昨天」，
     * 表现就是每天都没有温差，很难查。
     */
    internal fun expiredKeys(
        keys: Collection<String>,
        today: LocalDate,
        keepDays: Int = KEEP_DAYS,
    ): List<String> {
        val oldest = today.minusDays((keepDays - 1).toLong())
        return keys.filter { k ->
            val d = runCatching { LocalDate.parse(k.removePrefix(PREFIX)) }.getOrNull()
            // 解析不出来的（旧格式 / 手改）一并清掉
            d == null || d < oldest
        }
    }

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
