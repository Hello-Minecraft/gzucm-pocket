package com.gzucm.pocket

import com.gzucm.pocket.core.Campus
import com.gzucm.pocket.ui.login.LoginFlow
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 登录页默认选中哪个学段。
 *
 * 背景（致辉 2026-11-02 报）：
 *   「大部分是本科生用的，默认登录页应为本科生」
 *
 * 之前 `RootScreen` 传的是 `vm.campus`（当前数据属于哪个学段），
 * 于是本科生只要登过一次研究生账号（比如帮同学看课表），
 * 之后每次打开登录页都停在研究生，得手动点回来。
 *
 * 改成「默认本科 + 只有主动点过学段按钮才记住」。
 */
class LoginDefaultCampusTest {

    @Test
    fun `从没主动选过时默认本科`() {
        assertEquals(
            "大部分人用本科，没选过就该是本科",
            Campus.UNDERGRAD,
            LoginFlow.loginDefaultCampus(null),
        )
    }

    @Test
    fun `主动选过研究生就记住研究生`() {
        assertEquals(
            Campus.GRADUATE,
            LoginFlow.loginDefaultCampus(Campus.GRADUATE),
        )
    }

    @Test
    fun `主动选过本科也记住（和默认值一致）`() {
        assertEquals(
            Campus.UNDERGRAD,
            LoginFlow.loginDefaultCampus(Campus.UNDERGRAD),
        )
    }

    @Test
    fun `不会因为上次登录过研究生就默认研究生`() {
        /*
         * 这是 bug 的核心场景：
         *   · `campus`（当前数据是谁的）= GRADUATE
         *   · 但 `pickedCampus`（用户主动选过）= null
         *
         * 必须返回本科。传 null 就代表「没主动选过」，
         * 无论当前数据是什么学段都不该影响默认值。
         */
        assertEquals(
            "即使当前在研究生学段，没主动选过也要默认本科",
            Campus.UNDERGRAD,
            LoginFlow.loginDefaultCampus(picked = null),
        )
    }

    @Test
    fun `返回值永远不是 null（登录页必须有明确选中项）`() {
        for (p in listOf(null, Campus.UNDERGRAD, Campus.GRADUATE)) {
            val r = LoginFlow.loginDefaultCampus(p)
            assertEquals(
                "输入 $p 得到 $r，必须是有效学段",
                true,
                r == Campus.UNDERGRAD || r == Campus.GRADUATE,
            )
        }
    }
}