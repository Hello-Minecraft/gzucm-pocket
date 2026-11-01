package com.gzucm.pocket

import com.gzucm.pocket.data.Ecard
import com.gzucm.pocket.ui.power.EcardSyncResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 电费抓取的「收齐 → 出结果」判定。
 *
 * 这块出过两个难在真机上重现的 bug，都在这里钉住：
 *
 * **① 第二次打开面板就失败**（致辉 2026-11-01 报的）
 *   `done` 等状态用的是 `remember { }`，不随 `visible` 重置。
 *   面板关掉时 WebView 被销毁、状态却留着，第二次打开时 `done` 还是 true，
 *   `tryFinish()` 第一句 `if (done) return` 把所有新数据丢掉。
 *   → 修法：`remember(visible)`，每次打开都是干净的一轮。
 *
 * **② 失败时不回调**（同一天的另一个 bug）
 *   `fail()` 只设 `errorMsg` 不调 `onDone`，上层永远等不到结果，
 *   房间号停在换绑前的旧值。
 *   → 修法：`finishWithError()`，失败也回调。
 *
 * 这两个都是「状态机」层面的问题，所以把判定抽成 [EcardSyncResult.tryBuild]，
 * 用纯函数测，不依赖 Compose 和 WebView。
 */
class EcardSyncResultTest {

    /** 学校接口真实返回（2026-11-01 实测） */
    private val balanceJson = """
        {"ret":true,"msg":"查询成功","obj":{
          "roomNum":"5770","powerBalance":"130.96","du":"度",
          "schoolAreaNo":"2","buildingNo":"5754",
          "schoolArea":"广州中医药大学","building":"三元里6栋","room":"213",
          "formatPowerBalance":"130.96","formatPowerBalanceStr":"130.96度"
        }}
    """.trimIndent()

    private val dailyJson = """
        {"ret":true,"obj":{"dailyDetailsInfos":[
          {"accountTime":"2026-10-30","accountValue":"2.5"},
          {"accountTime":"2026-10-31","accountValue":"1.8"}
        ],"totalPage":1}}
    """.trimIndent()

    // ── 基本收齐判定 ────────────────────────────────────────────

    @Test
    fun `余额和明细都到齐就产出结果`() {
        val r = EcardSyncResult.tryBuild(balanceJson, dailyJson, null, "")
        assertTrue("应该是 Ok", r is EcardSyncResult.Ok)
        val ok = r as EcardSyncResult.Ok
        assertEquals("130.96", ok.info.balance.toString())
        assertEquals("三元里6栋   213", ok.info.roomLabel)
    }

    @Test
    fun `只有余额没有明细时继续等`() {
        // 不能提前收工：明细是另一半数据，缺了会显示成「没有用电记录」
        assertNull(EcardSyncResult.tryBuild(balanceJson, null, null, ""))
    }

    @Test
    fun `只有明细没有余额时继续等`() {
        assertNull(EcardSyncResult.tryBuild(null, dailyJson, null, ""))
    }

    @Test
    fun `全都没有时继续等`() {
        assertNull(EcardSyncResult.tryBuild(null, null, null, ""))
    }

    @Test
    fun `余额 JSON 坏掉时给出 Bad 而不是静默吞掉`() {
        // 静默返回 null 的话上层会一直等，界面卡在「正在取数」
        val r = EcardSyncResult.tryBuild("这不是 json", dailyJson, null, "")
        assertTrue("坏数据应该是 Bad", r is EcardSyncResult.Bad)
        assertTrue((r as EcardSyncResult.Bad).message.contains("余额接口返回异常"))
    }

    @Test
    fun `明细用 HTML 而不是 JSON 时也能收`() {
        val html = """
            <li class="mui-table-view-cell">
              <div class="accountTime">2026-10-30</div>
              <div class="accountName">日用电</div><div>2.5</div>
              <div class="accountName">剩余电量</div><div>50</div>
              <div class="accountName">总用电</div><div>20</div>
            </li>
        """.trimIndent()
        val r = EcardSyncResult.tryBuild(balanceJson, null, html, "")
        assertTrue(r is EcardSyncResult.Ok)
    }

    // ── bug① 的回归：第二次应该拿到新数据 ───────────────────────

    @Test
    fun `第二轮抓取必须产出和第一轮不同的结果`() {
        /*
         * 模拟「点两次更新」。
         *
         * 修复前：第二轮的数据因为 done 残留被直接丢弃 ——
         * 表现就是第一次能读到、第二次报错或没反应。
         *
         * 现在 tryBuild 是纯函数，没有内部 done，每轮独立。
         * 这条测试钉的是「第二轮的数据必须被正确处理」。
         */
        val round1 = EcardSyncResult.tryBuild(balanceJson, dailyJson, null, "")
        val secondBalance = balanceJson.replace("130.96", "128.40")
        val round2 = EcardSyncResult.tryBuild(secondBalance, dailyJson, null, "")

        assertTrue(round1 is EcardSyncResult.Ok)
        assertTrue(round2 is EcardSyncResult.Ok)
        assertEquals(130.96, (round1 as EcardSyncResult.Ok).info.balance, 0.001)
        assertEquals(
            "第二次必须是新值，不能被上一轮的 done 状态挡掉",
            128.40,
            (round2 as EcardSyncResult.Ok).info.balance,
            0.001,
        )
    }

    @Test
    fun `第二轮失败也不能被上一轮的成功挡住`() {
        // 第一次成功、第二次数据坏了 —— 第二次必须如实报 Bad
        val round1 = EcardSyncResult.tryBuild(balanceJson, dailyJson, null, "")
        assertTrue(round1 is EcardSyncResult.Ok)

        val round2 = EcardSyncResult.tryBuild("坏了", dailyJson, null, "")
        assertTrue("第二次失败也要有结果，不能沿用第一次的成功", round2 is EcardSyncResult.Bad)
    }

    // ── bug② 的回归：失败也要带出房间 ───────────────────────────

    @Test
    fun `余额解析失败时仍能拿到房间信息`() {
        // finishWithError 用 parseRoomOnly，房间要从坏掉的响应里尽量捞
        val roomOnlyJson = """
            {"ret":true,"obj":{
              "roomNum":"5770","schoolAreaNo":"2","buildingNo":"5754",
              "schoolArea":"广州中医药大学","building":"三元里6栋","room":"213"
            }}
        """.trimIndent()
        val info = Ecard.parseRoomOnly(roomOnlyJson, "")
        assertNotNull("失败路径也要能带出房间，否则卡片停在旧房间号", info)
        assertEquals(-1.0, info!!.balance, 0.001)
        assertEquals("三元里6栋   213", info.roomLabel)
    }
}