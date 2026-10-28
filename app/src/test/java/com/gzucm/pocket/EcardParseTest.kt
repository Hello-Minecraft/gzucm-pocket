package com.gzucm.pocket

import com.gzucm.pocket.data.Ecard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 电费解析（WebView 通道用）。
 *
 * 这个通道的意义：让页面自己发 fetch，cookie / referer / WAF 校验全交给浏览器，
 * 不再手动搬运 —— 之前正是手动搬 cookie 搬错了才一直读不到。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EcardParseTest {

    // 真实响应（抓包所得）
    private val balanceJson = """
        {"ret":true,"msg":"查询成功","obj":{"roomNum":"5770",
         "schoolAreaNo":"2","buildingNo":"5754","powerBalance":"11.78","du":"度"}}
    """.trimIndent()

    /**
     * 真实电费页片段（抓包所得）。
     * 余额是服务端渲染进 `#powerTips` 的，不需要调 getBalance 接口。
     */
    private val pageHtml = """
        <div id="p1" class="moreTips">
            <div id="powerTips">剩余电量：11.63度</div>
            <div id="detailsPower"><a href="/user/powerfee/toDailyDetails?implType=CGCOMMON1001&amp;type=2&amp;schoolAreaNo=2&amp;buildingNo=5754&amp;roomNum=5770&amp;from=&amp;token=node019w1ipnkbd65o1uj4tlx3re6wc4497">用电明细</a></div>
        </div>
        <input type="text" name="roomChooseText" value="三元里6栋   213" id="roomChoose">
        <input type="text" name="schoolAreaNo" style="display: none;" value="2" class="bind">
        <input type="text" name="buildingNo" style="display: none;" value="5754" class="bind">
        <input type="text" name="roomNum" style="display: none;" value="5770" class="bind">
        <script>var token = 'node019w1ipnkbd65o1uj4tlx3re6wc4497';</script>
    """.trimIndent()

    @Test
    fun `从页面 DOM 直接取余额`() {
        val info = Ecard.parseBalanceFromHtml(pageHtml)
        assertNotNull(info)
        assertEquals(11.63, info!!.balance, 0.001)
        assertEquals("度", info.unit)
        assertEquals("三元里6栋 213", info.roomLabel)
        assertEquals("2", info.schoolAreaNo)
        assertEquals("5754", info.buildingNo)
        assertEquals("5770", info.roomNum)
        assertEquals("node019w1ipnkbd65o1uj4tlx3re6wc4497", info.token)
    }

    @Test
    fun `登录页不该被当成有效数据`() {
        assertNull(Ecard.parseBalanceFromHtml("<html>请登录统一身份认证</html>"))
        assertNull(Ecard.parseBalanceFromHtml(""))
    }

    @Test
    fun `JS 通道给的 payload 优先用 roomLabel`() {
        val payload = """
            {"ret":true,"msg":"ok","obj":{"roomNum":"5770","schoolAreaNo":"2",
             "buildingNo":"5754","powerBalance":"11.63","du":"度",
             "roomLabel":"三元里6栋 213","token":"t1"}}
        """.trimIndent()
        val info = Ecard.parseBalance(payload, pageHtml = "")
        assertEquals("三元里6栋 213", info!!.roomLabel)
        assertEquals(11.63, info.balance, 0.001)
    }

    @Test
    fun `查询失败的返回不会被误当成数据`() {
        assertNull(Ecard.parseBalance("""{"ret":false,"msg":"查询失败","obj":null}""", pageHtml))
    }

    /**
     * 分页接口 getDailyDetails 的返回结构
     * （字段名从 powerdaily.html 的 getHtmlList 反推）
     */
    private val dailyJson = """
        {"ret":true,"msg":"ok","obj":{
          "totalPage":1,
          "dailyDetailsInfos":[
            {"accountTime":"2026-10-01","dailyUsed":"2.88","leftUsed":"74.04","leftFree":"0.00","totalUsed":"28.62"},
            {"accountTime":"2026-10-02","dailyUsed":"0.18","leftUsed":"73.86","leftFree":"0.00","totalUsed":"28.80"},
            {"accountTime":"2026-10-03","dailyUsed":"1.08","leftUsed":"72.78","leftFree":"0.00","totalUsed":"29.88"}
          ]}}
    """.trimIndent()

    /**
     * 真机实测的分页返回：日期字段是 **dateTime**（不是 accountTime）。
     * 之前猜成 accountTime，导致解析出 0 条。
     */
    private val realDailyJson = """
        {"ret":true,"msg":"ok","obj":{"dailyDetailsInfos":[
          {"dailyUsed":"2.88","leftUsed":"74.04","leftFree":"0.00","totalUsed":"28.62","dateTime":"2026-10-01"},
          {"dailyUsed":"0.18","leftUsed":"73.86","leftFree":"0.00","totalUsed":"28.80","dateTime":"2026-10-02"},
          {"dailyUsed":"1.08","leftUsed":"72.78","leftFree":"0.00","totalUsed":"29.88","dateTime":"2026-10-03"}
        ],"totalPage":1}}
    """.trimIndent()

    @Test
    fun `真机字段名 dateTime 能解析`() {
        val list = Ecard.parseDailyJson(realDailyJson)
        assertEquals(3, list.size)
        assertEquals("2026-10-01", list[0].date)
        assertEquals(2.88, list[0].usage, 0.001)
        assertEquals(74.04, list[0].remain, 0.001)
        assertEquals(28.62, list[0].total, 0.001)
        assertEquals("2026-10-03", list[2].date)
    }

    @Test
    fun `分页 JSON 明细解析`() {
        val list = Ecard.parseDailyJson(dailyJson)
        assertEquals(3, list.size)
        assertEquals("2026-10-01", list[0].date)
        assertEquals(2.88, list[0].usage, 0.001)
        assertEquals(74.04, list[0].remain, 0.001)
        assertEquals(28.62, list[0].total, 0.001)
        assertEquals("2026-10-03", list[2].date)
        assertEquals(1.08, list[2].usage, 0.001)
    }

    @Test
    fun `分页 JSON 乱序也能排好`() {
        val json = """
            {"ret":true,"obj":{"dailyDetailsInfos":[
              {"accountTime":"2026-10-09","dailyUsed":"2.0","leftUsed":"70.0"},
              {"accountTime":"2026-10-03","dailyUsed":"1.0","leftUsed":"71.0"}
            ]}}
        """.trimIndent()
        val list = Ecard.parseDailyJson(json)
        assertEquals("2026-10-03", list.first().date)
        assertEquals("2026-10-09", list.last().date)
    }

    @Test
    fun `ret 为 false 的分页返回空列表`() {
        assertTrue(Ecard.parseDailyJson("""{"ret":false,"msg":"查询失败","obj":null}""").isEmpty())
    }

    @Test
    fun `空明细数组返回空列表`() {
        assertTrue(
            Ecard.parseDailyJson("""{"ret":true,"obj":{"totalPage":0,"dailyDetailsInfos":[]}}""").isEmpty()
        )
    }

    @Test
    fun `非 JSON 不崩`() {
        assertTrue(Ecard.parseDailyJson("<html>login</html>").isEmpty())
        assertTrue(Ecard.parseDailyJson("").isEmpty())
    }

    /**
     * 回归：`id="powerTips"` 在真实页面里出现两次，
     * 第二个（隐藏在 roomInput 区）是空的。必须取第一个有文字的。
     */
    @Test
    fun `powerTips 重复出现时取有值的那个`() {
        val html = """
            <div id="p1" class="moreTips">
              <div id="powerTips">剩余电量：11.63度</div>
            </div>
            <div id="roomInput" style="display: none;">
              <div id="powerTips" style="color:grey;margin-bottom:10px;"></div>
            </div>
            <input type="text" name="roomNum" value="5770">
            <script>var token = 'abc123';</script>
        """.trimIndent()
        val info = Ecard.parseBalanceFromHtml(html)
        assertEquals(11.63, info!!.balance, 0.001)
    }

    @Test
    fun `余额为 0 也算有效数据`() {
        // 真的用到 0 度时不能当成「没数据」
        val html = """
            <div id="powerTips">剩余电量：0.00度</div>
            <input type="text" name="roomNum" value="5770">
            <script>var token = 'abc123';</script>
        """.trimIndent()
        val info = Ecard.parseBalanceFromHtml(html)
        assertNotNull(info)
        assertEquals(0.0, info!!.balance, 0.001)
    }

    /**
     * 回归：接口值带单位时不能解析成 0。
     * 真机曾出现 `EMIT balance=3.23度` → Kotlin toDoubleOrNull 失败 → 显示 0 度。
     */
    @Test
    fun `余额带单位也能解析`() {
        val withUnit = """
            {"ret":true,"obj":{"roomNum":"5770","schoolAreaNo":"2",
             "buildingNo":"5754","formatPowerBalanceStr":"3.23度","du":"度"}}
        """.trimIndent()
        assertEquals(3.23, Ecard.parseBalance(withUnit, "")!!.balance, 0.001)

        val withPrefix = """
            {"ret":true,"obj":{"roomNum":"5770",
             "formatPowerBalanceStr":"剩余电量：11.63"}}
        """.trimIndent()
        assertEquals(11.63, Ecard.parseBalance(withPrefix, "")!!.balance, 0.001)
    }

    /**
     * 余额为空时必须返回 null，**绝不能返回 0**。
     * 曾经这里返回 0，未登录时把「155 度」覆盖成「0 度」。
     */
    @Test
    fun `余额为空字符串返回 null 而不是0`() {
        val empty = """{"ret":true,"obj":{"roomNum":"5770","formatPowerBalanceStr":""}}"""
        assertNull(Ecard.parseBalance(empty, ""))
        val emptyBoth = """{"ret":true,"obj":{"roomNum":"5770"}}"""
        assertNull(Ecard.parseBalance(emptyBoth, ""))
        val noDigit = """{"ret":true,"obj":{"formatPowerBalanceStr":"--"}}"""
        assertNull(Ecard.parseBalance(noDigit, ""))
    }

    @Test
    fun `余额解析`() {
        val info = Ecard.parseBalance(balanceJson, pageHtml = "")
        assertNotNull(info)
        assertEquals(11.78, info!!.balance, 0.001)
        assertEquals("度", info.unit)
        assertEquals("5770", info.roomNum)
        assertEquals("2", info.schoolAreaNo)
        assertEquals("5754", info.buildingNo)
    }

    @Test
    fun `优先读 roomChooseText 而不是猜`() {
        // 真实页面片段（抓包所得）
        val html = """
            <input type="text" name="roomChooseText" value="三元里6栋   213" id="roomChoose">
            <input type="text" name="schoolAreaNo" style="display: none;" value="2">
            <input type="text" name="buildingNo" style="display: none;" value="5754">
            <input type="text" name="roomNum" style="display: none;" value="5770">
        """.trimIndent()
        val info = Ecard.parseBalance(balanceJson, html)
        // 多余空格要被规范化
        assertEquals("三元里6栋 213", info!!.roomLabel)
    }

    @Test
    fun `属性顺序变化也能读到`() {
        val html = """<input id="roomChoose" value="大学城C15-302" name="roomChooseText">"""
        val info = Ecard.parseBalance(balanceJson, html)
        assertEquals("大学城C15-302", info!!.roomLabel)
    }

    @Test
    fun `没有 roomChooseText 时退回校区名`() {
        val html = """<div>大学城校区 C15-302</div>"""
        val info = Ecard.parseBalance(balanceJson, html)
        assertEquals("大学城校区 C15-302", info!!.roomLabel)
    }

    @Test
    fun `没有房间名时退回房间号`() {
        val info = Ecard.parseBalance(balanceJson, "")
        assertEquals("5770", info!!.roomLabel)
    }
    @Test
    fun `ret 为 false 时返回 null`() {
        assertNull(Ecard.parseBalance("""{"ret":false,"msg":"未登录"}""", ""))
    }

    @Test
    fun `不是 JSON 时返回 null 而不是抛异常`() {
        assertNull(Ecard.parseBalance("<html>登录页</html>", ""))
    }

    @Test
    fun `明细解析`() {
        val html = """
        <li class="mui-table-view-cell"> <a href="#">
        <div class="mui-row"><div class="accountName">日用电量(度)</div>
        <div class="text-right">0.18</div></div>
        <div class="mui-row"><div class="accountName">剩余电量(度)</div>
        <div class="text-right">73.86</div></div>
        <div class="mui-row"><div class="accountName">总用电量(度)</div>
        <div class="text-right">28.80</div></div>
        <div class="accountTime">2026-10-01 </div>
        </a> </li>
        <li class="mui-table-view-cell"> <a href="#">
        <div class="mui-row"><div class="accountName">日用电量(度)</div>
        <div class="text-right">1.08</div></div>
        <div class="mui-row"><div class="accountName">剩余电量(度)</div>
        <div class="text-right">72.78</div></div>
        <div class="mui-row"><div class="accountName">总用电量(度)</div>
        <div class="text-right">29.88</div></div>
        <div class="accountTime">2026-10-02 </div>
        </a> </li>
        """.trimIndent()

        val list = Ecard.parseDaily(html)
        assertEquals(2, list.size)
        assertEquals("2026-10-01", list[0].date)
        assertEquals(0.18, list[0].usage, 0.001)
        assertEquals(73.86, list[0].remain, 0.001)
        assertEquals("2026-10-02", list[1].date)
        assertEquals(1.08, list[1].usage, 0.001)
    }

    @Test
    fun `明细空页面返回空列表`() {
        assertTrue(Ecard.parseDaily("<html></html>").isEmpty())
        assertTrue(Ecard.parseDaily("").isEmpty())
    }

    @Test
    fun `明细按日期升序`() {
        val html = """
        <li class="mui-table-view-cell">
        <div class="accountName">日用电量(度)</div><div>2.0</div>
        <div class="accountName">剩余电量(度)</div><div>70.0</div>
        <div class="accountTime">2026-10-09 </div></li>
        <li class="mui-table-view-cell">
        <div class="accountName">日用电量(度)</div><div>1.0</div>
        <div class="accountName">剩余电量(度)</div><div>71.0</div>
        <div class="accountTime">2026-10-03 </div></li>
        """.trimIndent()
        val list = Ecard.parseDaily(html)
        assertEquals("2026-10-03", list.first().date)
        assertEquals("2026-10-09", list.last().date)
    }
}