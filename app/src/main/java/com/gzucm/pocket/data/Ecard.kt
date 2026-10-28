package com.gzucm.pocket.data

import com.gzucm.pocket.core.Net
import com.gzucm.pocket.core.NetError
import com.gzucm.pocket.core.PowerDaily
import com.gzucm.pocket.core.PowerInfo
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 校园一卡通（ecard.gzucm.edu.cn）。
 *
 * 电费这块的鉴权方式和教务不同：页面渲染时下发一个短 token，查询时把它作为参数带上。
 * 房间号（schoolAreaNo / buildingNo / roomNum）随页面一起渲染，说明是账号绑定的，
 * 不需要用户选楼栋。
 */
object Ecard {

    const val BASE = "https://ecard.gzucm.edu.cn"

    private const val POWER_PAGE = "$BASE/user/powerfee/index"

    private val TOKEN_RE = Regex("""[?&]token=([A-Za-z0-9]+)""")
    private val AREA_RE = Regex("""schoolAreaNo["']?\s*[:=]\s*["']?(\d+)""")
    private val BUILDING_RE = Regex("""buildingNo["']?\s*[:=]\s*["']?(\d+)""")
    private val ROOM_RE = Regex("""roomNum["']?\s*[:=]\s*["']?(\w+)""")

    /**
     * 解析分页接口 `getDailyDetails` 返回的 JSON。
     *
     * 结构（从 powerdaily.html 的 `getHtmlList` 反推）：
     * `{ret:true, obj:{dailyDetailsInfos:[{accountTime, dailyUsed, leftUsed, leftFree, totalUsed}], totalPage}}`
     *
     * 这是**拿整月数据唯一可靠的途径**：`toDailyDetails` 页面每页只渲染 5 条。
     */
    fun parseDailyJson(text: String): List<PowerDaily> {
        val root = runCatching { Net.json.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: return emptyList()
        if (root.str("ret") != "true") return emptyList()
        val obj = root["obj"]?.jsonObject ?: return emptyList()
        val arr = obj["dailyDetailsInfos"]?.jsonArray ?: return emptyList()

        val out = ArrayList<PowerDaily>(arr.size)
        for (el in arr) {
            val o = runCatching { el.jsonObject }.getOrNull() ?: continue
            // 真机实测字段名是 dateTime；其余为兼容写法
            val date = listOf("dateTime", "accountTime", "date", "time")
                .map { o.str(it) }
                .firstOrNull { it.isNotBlank() }
                ?.replace('/', '-')
                ?.take(10)
                ?: continue
            val usage = o.str("dailyUsed").toDoubleOrNull() ?: 0.0
            val remain = o.str("leftUsed").toDoubleOrNull() ?: 0.0
            val total = o.str("totalUsed").toDoubleOrNull() ?: 0.0
            out += PowerDaily(date = date, usage = usage, remain = remain, total = total)
        }
        return out.sortedBy { it.date }
    }

    /** 从任意字符串里抽出第一个数字，抽不到返回 0.0。容忍「3.23度」「剩余电量：3.23」 */
    private fun numOf(s: String): Double =
        Regex("""([\d]+(?:\.[\d]+)?)""").find(s)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0

    /**
     * 从电费页 HTML 里取余额。纯函数，可测。
     *
     * 两个坑（从真实页面源码确认）：
     * 1. `id="powerTips"` **出现两次**，第二个（隐藏在 roomInput 区）是空的。
     *    必须遍历所有匹配项，取第一个有文字的。
     * 2. 余额文本形如「剩余电量：11.63度」，取第一个数字。
     */
    fun parseBalanceFromHtml(html: String): PowerInfo? {
        if (html.isBlank()) return null

        // 遍历所有 id="powerTips" 的元素，取第一个内容非空的
        val tips = Regex("""id="powerTips"[^>]*>([^<]*)""")
            .findAll(html)
            .map { it.groupValues[1].trim() }
            .firstOrNull { it.isNotBlank() }
            .orEmpty()

        val num = Regex("""([\d.]+)""").find(tips)?.groupValues?.get(1).orEmpty()

        val token = Regex("""var token\s*=\s*['"]([A-Za-z0-9]+)['"]""")
            .find(html)?.groupValues?.get(1).orEmpty()
        val area = Regex("""name="schoolAreaNo"[^>]*value="(\d+)""")
            .find(html)?.groupValues?.get(1).orEmpty()
        val building = Regex("""name="buildingNo"[^>]*value="(\d+)""")
            .find(html)?.groupValues?.get(1).orEmpty()
        val room = Regex("""name="roomNum"[^>]*value="(\w+)""")
            .find(html)?.groupValues?.get(1).orEmpty()

        // 页面里连 token 和房间都没有 → 多半没登录
        if (token.isBlank() && room.isBlank() && area.isBlank()) return null

        return PowerInfo(
            balance = num.toDoubleOrNull() ?: 0.0,
            unit = "度",
            roomLabel = roomLabel(html, room),
            token = token,
            schoolAreaNo = area,
            buildingNo = building,
            roomNum = room,
        )
    }

    /**
     * 拉一次电费页，从**页面 DOM** 里取余额。
     *
     * 关键：余额是服务端渲染进 `#powerTips` 的（「剩余电量：11.63度」），
     * 不需要再调 `getBalance` —— 那个接口是给「查别人房间」用的，
     * 参数签名不同，按自己的房间调会返回 `{"ret":false,"msg":"查询失败"}`。
     */
    fun fetchPower(): PowerInfo {
        val html = Net.get(POWER_PAGE, referer = "$BASE/user/index")
        if (html.contains("authserver.gzucm.edu.cn") || html.contains("统一身份认证")) {
            throw NetError.Unauthorized(200)
        }
        return parseBalanceFromHtml(html)
            ?: throw NetError.Unauthorized(200)
    }

    /**
     * 逐月用电明细。
     *
     * 走 `getDailyDetails` 分页接口（`lastDate` 传 `yyyy-mm`），
     * 而不是解析 `toDailyDetails` 页面 —— 那个页面每页只渲染 5 条，
     * 直接读 DOM 只能拿到最近 5 天，所以之前「当月每日明细」永远是空的。
     */
    fun fetchPowerDaily(info: PowerInfo, month: String? = null): List<PowerDaily> {
        val ym = month ?: java.text.SimpleDateFormat("yyyy-MM", java.util.Locale.US)
            .format(java.util.Date())

        val all = LinkedHashMap<String, PowerDaily>()
        var page = 1
        while (page <= 12) {
            val text = runCatching {
                Net.postForm(
                    "$BASE/user/powerfee/getDailyDetails",
                    mapOf(
                        "roomNum" to info.roomNum,
                        "lastDate" to ym,
                        "type" to "2",
                        "implType" to "CGCOMMON1001",
                        "schoolAreaNo" to info.schoolAreaNo,
                        "pageNum" to "$page",
                        "pageSize" to "30",
                        "token" to info.token,
                        "from" to "",
                    ),
                    referer = POWER_PAGE,
                )
            }.getOrNull() ?: break

            val batch = parseDailyJson(text)
            if (batch.isEmpty()) break
            val before = all.size
            batch.forEach { all[it.date] = it }
            // 没有新增说明翻到底了
            if (all.size == before) break
            if (batch.size < 30) break
            page++
        }
        return all.values.sortedBy { it.date }
    }

    /**
     * 解析余额 JSON。WebView 通道与 OkHttp 通道共用。
     *
     * 优先用 `obj.roomLabel`（JS 从 `roomChooseText` 读的，如「三元里6栋 213」）；
     * 没有就从 `pageHtml` 里捞；再没有就退回内部房间号。
     */
    fun parseBalance(text: String, pageHtml: String): PowerInfo? {
        val root = runCatching { Net.json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
        if (root.str("ret") != "true") return null
        val obj = root["obj"]?.jsonObject ?: return null
        // 真实字段是 formatPowerBalanceStr；powerBalance 作为兼容。
        // 值可能带单位（「3.23度」「剩余电量：3.23」），统一剥掉非数字字符。
        val raw = obj.str("formatPowerBalanceStr").ifBlank { obj.str("powerBalance") }
        // 关键：拿不到数字就返回 null，绝不返回 0。
        // 否则未登录时会被当成「余额 0 度」，把好数据覆盖掉，界面上就只剩 0。
        if (raw.isBlank()) return null
        val balance = numOf(raw)
        if (raw.none { it.isDigit() }) return null
        val unit = obj.str("du").ifBlank { "度" }
        val room = obj.str("roomNum")
        val label = when {
            obj.str("roomLabel").isNotBlank() -> obj.str("roomLabel")
            pageHtml.isNotBlank() -> roomLabel(pageHtml, room)
            room.isNotBlank() -> room
            else -> "宿舍"
        }
        return PowerInfo(
            balance = balance,
            unit = unit,
            roomLabel = label,
            token = obj.str("token"),
            schoolAreaNo = obj.str("schoolAreaNo"),
            buildingNo = obj.str("buildingNo"),
            roomNum = room,
        )
    }

    /** 解析 `toDailyDetails` 返回的 HTML 列表。 */
    fun parseDaily(html: String): List<PowerDaily> {
        val blockRe = Regex("""<li class="mui-table-view-cell">(.*?)</li>""", RegexOption.DOT_MATCHES_ALL)
        val dateRe = Regex("""accountTime">\s*(\d{4}-\d{2}-\d{2})""")
        val kvRe = Regex("""accountName">([^<]+)</div>\s*<div[^>]*>\s*([\d.]+)""")

        val out = ArrayList<PowerDaily>()
        for (block in blockRe.findAll(html)) {
            val body = block.groupValues[1]
            val date = dateRe.find(body)?.groupValues?.get(1) ?: continue
            var usage = 0.0
            var remain = 0.0
            var total = 0.0
            for (kv in kvRe.findAll(body)) {
                val label = kv.groupValues[1]
                val value = kv.groupValues[2].toDoubleOrNull() ?: continue
                when {
                    label.contains("日用电") -> usage = value
                    label.contains("剩余电量") -> remain = value
                    label.contains("总用电") -> total = value
                }
            }
            if (usage == 0.0 && remain == 0.0) continue
            out += PowerDaily(date = date, usage = usage, remain = remain, total = total)
        }
        return out.sortedBy { it.date }
    }


    /**
     * 房间显示名。
     *
     * 电费页里有四个相关字段：
     *   - `schoolAreaNo` / `buildingNo` / `roomNum` → 系统内部 ID（如 2 / 5754 / 5770）
     *   - `roomChooseText` → 给人看的名字（如「三元里6栋   213」）
     * 前三者随账号绑定、因人而异，由服务端渲染进页面，用户不用选。
     * 所以这里优先读 `roomChooseText`，而不是靠「三元里/大学城」去猜。
     */
    private fun roomLabel(html: String, roomNum: String): String {
        // 逐个人 tag 里找，避免属性顺序变化导致正则失效
        for (tag in Regex("""<input[^>]*>""").findAll(html)) {
            val t = tag.value
            if (t.contains("roomChooseText") || t.contains("id=\"roomChoose\"")) {
                val v = Regex("""value="([^"]*)"""")
                    .find(t)?.groupValues?.get(1)
                    ?.trim()
                    ?.replace(Regex("""\s+"""), " ")
                if (!v.isNullOrBlank() && v.length in 2..30) return v
            }
        }
        // 退回：兜底从可见文本里找校区名
        for (re in listOf(
            Regex("""房间号[:：]?\s*</?[^>]*>?\s*([\u4e00-\u9fa5A-Za-z0-9\-]{2,20})"""),
            Regex("""((?:三元里|大学城)[^\u003c\u003e"'\n]{0,18})"""),
        )) {
            val m = re.find(html)?.groupValues?.get(1)?.trim()
            if (!m.isNullOrBlank() && m.length in 2..20 && !m.contains("房间")) return m
        }
        return if (roomNum.isNotBlank()) "房间 $roomNum" else "宿舍"
    }
}
