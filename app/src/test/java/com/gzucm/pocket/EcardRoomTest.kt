package com.gzucm.pocket

import com.gzucm.pocket.data.Ecard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 电费房间号和余额解耦。
 *
 * 背景（2026-11-01 致辉报的）：
 *   他在小程序里把宿舍从「2栋 409」换绑成「6栋 211」，
 *   但 App 电费卡片一直显示旧房间号，弹窗还报「余额为空」。
 *
 * 根因有两条，都在这里钉住：
 *   ① 取余额失败时 `tryFinish()` 永远不往下走 → `onDone` 从不调用
 *   ② 即使能拿到房间，也被绑在「余额必须成功」上一起丢弃了
 */
class EcardRoomTest {

    /** 模拟接口返回：有房间、有余额 */
    private val withBalance = """
        {"ret":true,"obj":{
          "formatPowerBalanceStr":"144.09",
          "du":"度","roomNum":"211","schoolAreaNo":"2","buildingNo":"5754",
          "roomLabel":"三元里6栋   211","token":"abc123"
        }}
    """.trimIndent()

    /** 模拟接口返回：只有房间，没有余额（页面改版/字段变了） */
    private val roomOnly = """
        {"ret":true,"obj":{
          "roomNum":"211","schoolAreaNo":"2","buildingNo":"5754",
          "roomLabel":"三元里6栋   211","token":"abc123"
        }}
    """.trimIndent()

    @Test
    fun `正常情况余额和房间都解析出来`() {
        val info = Ecard.parseBalance(withBalance, "")
        assertNotNull(info)
        assertEquals(144.09, info!!.balance, 0.001)
        assertEquals("三元里6栋   211", info.roomLabel)
        assertEquals("211", info.roomNum)
    }

    @Test
    fun `只有房间时 parseBalance 返回 null`() {
        // 这是原来的行为，也是死结的起点：拿不到余额就整条丢掉
        assertNull(Ecard.parseBalance(roomOnly, ""))
    }

    @Test
    fun `只有房间时 parseRoomOnly 能拿到房间信息`() {
        val info = Ecard.parseRoomOnly(roomOnly, "")
        assertNotNull("房间信息不该因为余额缺失就丢掉", info)
        assertEquals("三元里6栋   211", info!!.roomLabel)
        assertEquals("211", info.roomNum)
        assertEquals("2", info.schoolAreaNo)
    }

    @Test
    fun `parseRoomOnly 用 -1 表示余额未知`() {
        val info = Ecard.parseRoomOnly(roomOnly, "")
        assertEquals(
            "必须用负数标记未知，上层靠它判断「只更新房间」",
            -1.0,
            info!!.balance,
            0.001,
        )
    }

    @Test
    fun `房间字段全空时 parseRoomOnly 返回 null`() {
        // 没登录 / 页面没渲染完，不该编一个假房间出来
        val empty = """{"ret":true,"obj":{"formatPowerBalanceStr":"0"}}"""
        assertNull(Ecard.parseRoomOnly(empty, ""))
    }

    @Test
    fun `坏 JSON 不会崩`() {
        assertNull(Ecard.parseRoomOnly("not json", ""))
        assertNull(Ecard.parseBalance("not json", ""))
    }

    @Test
    fun `余额为 0 和余额未知是两回事`() {
        // 真没电了 vs 没读到，界面上必须能区分
        val zero = """{"ret":true,"obj":{"formatPowerBalanceStr":"0","roomNum":"211","schoolAreaNo":"2"}}"""
        val info = Ecard.parseBalance(zero, "")
        assertNotNull(info)
        assertEquals("真 0 度要如实显示", 0.0, info!!.balance, 0.001)
        assertTrue("真 0 度不是未知", info.balance >= 0)

        val unknown = Ecard.parseRoomOnly(roomOnly, "")
        assertTrue("未知必须是负数", unknown!!.balance < 0)
    }

    @Test
    fun `roomChooseText 优先于内部 ID`() {
        // 页面里有 roomChooseText 时用它，别把 schoolAreaNo=2 当栋号
        val html = """<input id="roomChooseText" value="三元里6栋   211" name="roomChooseText">"""
        val info = Ecard.parseRoomOnly(roomOnly, html)
        assertEquals("三元里6栋   211", info!!.roomLabel)
    }

    // ── 真实返回结构（2026-11-01 实测 getBalance）──────────────

    /** 这是学校接口真实返回，字段一个没改 */
    private val realResponse = """
        {"ret":true,"msg":"查询成功","obj":{
          "roomNum":"5770","powerBalance":"130.96","du":"度",
          "schoolAreaNo":"2","buildingNo":"5754",
          "schoolArea":"广州中医药大学","building":"三元里6栋","room":"213",
          "formatPowerBalance":"130.96","formatPowerBalanceStr":"130.96度"
        }}
    """.trimIndent()

    @Test
    fun `真实返回能正确解析余额`() {
        val info = Ecard.parseBalance(realResponse, "")
        assertNotNull(info)
        assertEquals("130.96", info!!.balance.toString())
    }

    @Test
    fun `房间标签用接口的 building 加 room，不用内部 ID`() {
        val info = Ecard.parseBalance(realResponse, "")
        assertEquals(
            "必须拼「三元里6栋   213」，不能显示成「2栋 5754」",
            "三元里6栋   213",
            info!!.roomLabel,
        )
    }

    @Test
    fun `页面 HTML 里的旧 roomChooseText 不能盖掉接口值`() {
        /*
         * 换绑后 #roomChooseText 可能还是旧值（致辉遇到：换绑成 211 后它仍写 213）。
         * 接口返回的才是当前绑定，优先级必须在 HTML 之上。
         */
        val html = """<input type="text" name="roomChooseText" value="三元里2栋   409" disabled>"""
        val info = Ecard.parseBalance(realResponse, html)
        assertEquals(
            "接口值优先，不能被页面里的旧 input 覆盖",
            "三元里6栋   213",
            info!!.roomLabel,
        )
    }

    @Test
    fun `接口没给 building 时才退回 HTML`() {
        val noBuilding = """
            {"ret":true,"obj":{
              "roomNum":"5770","formatPowerBalanceStr":"130.96度","du":"度"
            }}
        """.trimIndent()
        val html = """<input type="text" name="roomChooseText" value="三元里6栋   211" disabled>"""
        val info = Ecard.parseBalance(noBuilding, html)
        assertEquals("三元里6栋 211", info!!.roomLabel)
    }
}