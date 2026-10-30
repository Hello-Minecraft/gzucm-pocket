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

    // ── 进电费页是否自动弹窗同步 ─────────────────────────────────

    private val hour = 60 * 60_000L

    @Test
    fun `从没同步过要自动弹`() {
        assertTrue(PowerAlert.needsAutoSync(0L, 0L, now))
    }

    @Test
    fun `十分钟前刚同步过不弹`() {
        assertFalse(PowerAlert.needsAutoSync(now - 10 * 60_000L, 0L, now))
    }

    @Test
    fun `超过两小时要弹`() {
        assertTrue(PowerAlert.needsAutoSync(now - 3 * hour, 0L, now))
    }

    @Test
    fun `刚好两小时要弹`() {
        assertTrue(PowerAlert.needsAutoSync(now - 2 * hour, 0L, now))
    }

    @Test
    fun `刚失败过先别弹 免得反复打断`() {
        // 5 分钟前试过、上次成功是 3 小时前 → 冷却期内不弹
        val attempted = now - 5 * 60_000L
        assertFalse(PowerAlert.needsAutoSync(now - 3 * hour, attempted, now))
    }

    @Test
    fun `冷却过后可以再弹`() {
        val attempted = now - 30 * 60_000L
        assertTrue(PowerAlert.needsAutoSync(now - 3 * hour, attempted, now))
    }

    @Test
    fun `失败但已成功同步过就不受失败冷却影响`() {
        // 上次尝试早于上次成功（说明之后成功过），照常按 staleness 判断
        val checked = now - 3 * hour
        val attempted = checked - 60_000L
        assertTrue(PowerAlert.needsAutoSync(checked, attempted, now))
    }
}
