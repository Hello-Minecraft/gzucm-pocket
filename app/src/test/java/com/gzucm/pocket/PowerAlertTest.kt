package com.gzucm.pocket

import com.gzucm.pocket.core.PowerAlert
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 电费不足提醒的判定逻辑。
 *
 * 关键取舍：巡检**不联网**，只读上次同步算好的可用天数。
 * 所以这里重点测「什么时候不该提醒」—— 误报比漏报更烦人。
 */
class PowerAlertTest {

    private val now = 1_760_000_000_000L
    private val fresh = now - 3_600_000L      // 1 小时前
    private val stale = now - 72 * 3_600_000L // 3 天前（超过 48h 阈值）
    private val today = "2026-10-28"

    @Test
    fun `不足3天且数据新鲜要提醒`() {
        assertTrue(PowerAlert.shouldNotify(1.5f, fresh, null, today, now))
        assertTrue(PowerAlert.shouldNotify(2.9f, fresh, null, today, now))
    }

    @Test
    fun `够3天不提醒`() {
        assertFalse(PowerAlert.shouldNotify(3f, fresh, null, today, now))
        assertFalse(PowerAlert.shouldNotify(10f, fresh, null, today, now))
    }

    @Test
    fun `今天已经提醒过就不再提醒`() {
        assertFalse(PowerAlert.shouldNotify(1f, fresh, today, today, now))
    }

    @Test
    fun `昨天提醒过今天可以再提醒`() {
        assertTrue(PowerAlert.shouldNotify(1f, fresh, "2026-10-27", today, now))
    }

    @Test
    fun `数据太旧不提醒`() {
        // 超过 48 小时的旧数据，宁可不说也别误报
        assertFalse(PowerAlert.shouldNotify(1f, stale, null, today, now))
    }

    @Test
    fun `没有数据不提醒`() {
        assertFalse(PowerAlert.shouldNotify(-1f, fresh, null, today, now))
        assertFalse(PowerAlert.shouldNotify(0f, fresh, null, today, now))
        assertFalse(PowerAlert.shouldNotify(1f, 0L, null, today, now))
    }

    @Test
    fun `阈值可调`() {
        assertFalse(PowerAlert.shouldNotify(4f, fresh, null, today, now, threshold = 3f))
        assertTrue(PowerAlert.shouldNotify(4f, fresh, null, today, now, threshold = 7f))
    }

    // ── 静默同步频率 ─────────────────────────────────────────────

    @Test
    fun `从没同步过要跑`() {
        assertTrue(PowerAlert.shouldSilentSync(null, today))
    }

    @Test
    fun `今天同步过就不再跑`() {
        assertFalse(PowerAlert.shouldSilentSync(today, today))
    }

    @Test
    fun `昨天同步过今天要跑`() {
        assertTrue(PowerAlert.shouldSilentSync("2026-10-27", today))
    }
}
