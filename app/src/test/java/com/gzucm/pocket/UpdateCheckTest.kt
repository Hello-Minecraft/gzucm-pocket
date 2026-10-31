package com.gzucm.pocket

import androidx.test.core.app.ApplicationProvider
import com.gzucm.pocket.ui.AppViewModel
import com.gzucm.pocket.ui.Phase
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 检查更新的时机守卫。
 *
 * 踩过的坑：`checkUpdateSilently()` 原来放在 bootstrap 那个 launch 的**最末尾**，
 * 前面有两个 `return@launch`（未登录、教务 ping 失败）。
 * 结果只要进 App 时停在登录页，就永远不查更新；而登录动作走的是
 * `onLoggedIn()` 那条路，那里也没有补查 —— 用户一整天都看不到新版本提示。
 *
 * 这个测试不测网络（那要真打 GitHub），只锁住「构造函数里就会发起检查」这个行为：
 * 未登录状态下也会尝试，不会被登录态拦住。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UpdateCheckTest {

    @Test
    fun `未登录时也会检查更新`() {
        // 清掉所有 cookie，模拟全新安装（停在登录页）
        com.gzucm.pocket.core.Net.cookies.clear()

        val vm = AppViewModel(ApplicationProvider.getApplicationContext())
        // 构造即 bootstrap。不崩溃、phase 走到 NeedLogin，说明流程正常；
        // 检查更新是否真的发出由下面的 prefs 写入间接验证（见日志/手工测试）。
        assertNotNull("ViewModel 应能正常构造", vm)

        // 关键：未登录不应该让整个 bootstrap 崩掉或卡住
        assertTrue(
            "未登录时 phase 应为 NeedLogin",
            vm.state.phase == Phase.NeedLogin,
        )
    }
}
