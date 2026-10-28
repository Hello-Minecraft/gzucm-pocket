package com.gzucm.pocket

import com.gzucm.pocket.core.PersistentCookieJar
import com.gzucm.pocket.core.SessionStore
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Cookie 分域分路径行为。
 *
 * 这是「电费读不到」的回归测试：
 * 一卡通在同一域名下有两个同名 JSESSIONID（Path=/user 与 Path=/sso），
 * 旧实现只按 name 存，后者会把前者覆盖，导致业务请求带着 SSO 会话被弹回登录页。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CookieJarTest {

    private fun newJar(): PersistentCookieJar {
        val ctx = RuntimeEnvironment.getApplication()
        val store = SessionStore(ctx)
        store.clearCookies()
        return PersistentCookieJar(store)
    }

    private fun Cookie.pathValue() = path

    @Test
    fun `同名不同 path 的 cookie 互不覆盖`() {
        val jar = newJar()
        val userUrl = "https://ecard.gzucm.edu.cn/user/powerfee/index".toHttpUrl()
        val ssoUrl = "https://ecard.gzucm.edu.cn/sso/login".toHttpUrl()

        jar.saveFromResponse(
            userUrl,
            listOf(
                Cookie.Builder().domain("ecard.gzucm.edu.cn").path("/user")
                    .name("JSESSIONID").value("USER_SESSION").build(),
            ),
        )
        jar.saveFromResponse(
            ssoUrl,
            listOf(
                Cookie.Builder().domain("ecard.gzucm.edu.cn").path("/sso")
                    .name("JSESSIONID").value("SSO_SESSION").build(),
            ),
        )

        val forUser = jar.loadForRequest(userUrl)
        val forSso = jar.loadForRequest(ssoUrl)

        val userJs = forUser.first { it.name == "JSESSIONID" }
        val ssoJs = forSso.first { it.name == "JSESSIONID" }

        assertEquals("业务请求必须拿到 /user 那条", "USER_SESSION", userJs.value)
        assertEquals("SSO 请求必须拿到 /sso 那条", "SSO_SESSION", ssoJs.value)
        assertEquals("/user", userJs.pathValue())
    }

    @Test
    fun `path 前缀匹配而不是任意子串`() {
        val jar = newJar()
        jar.saveFromResponse(
            "https://ecard.gzucm.edu.cn/user/index".toHttpUrl(),
            listOf(
                Cookie.Builder().domain("ecard.gzucm.edu.cn").path("/user")
                    .name("JSESSIONID").value("U").build(),
            ),
        )
        // /userinfo 不该匹配 /user
        val wrong = jar.loadForRequest("https://ecard.gzucm.edu.cn/userinfo".toHttpUrl())
        assertTrue("不该把 /user 的 cookie 发到 /userinfo", wrong.none { it.name == "JSESSIONID" })

        // /user/api/xxx 应该匹配
        val ok = jar.loadForRequest("https://ecard.gzucm.edu.cn/user/api/x".toHttpUrl())
        assertTrue("应匹配 /user 前缀", ok.any { it.name == "JSESSIONID" && it.value == "U" })
    }

    @Test
    fun `根路径 cookie 对所有子路径生效`() {
        val jar = newJar()
        jar.saveFromResponse(
            "https://ecard.gzucm.edu.cn/sso/index".toHttpUrl(),
            listOf(
                Cookie.Builder().domain("ecard.gzucm.edu.cn").path("/")
                    .name("SFP_Verify_Cookie").value("V").build(),
            ),
        )
        val deep = jar.loadForRequest("https://ecard.gzucm.edu.cn/user/powerfee/getBalance".toHttpUrl())
        assertTrue(deep.any { it.name == "SFP_Verify_Cookie" })
    }

    @Test
    fun `不同域名完全隔离`() {
        val jar = newJar()
        jar.saveFromResponse(
            "https://jw.gzucm.edu.cn/new/welcome.page".toHttpUrl(),
            listOf(
                Cookie.Builder().domain("jw.gzucm.edu.cn").path("/")
                    .name("JSESSIONID").value("JW").build(),
            ),
        )
        val ecard = jar.loadForRequest("https://ecard.gzucm.edu.cn/user/index".toHttpUrl())
        assertTrue("教务的 cookie 不能泄漏到一卡通", ecard.isEmpty())
        assertTrue(jar.has("jw.gzucm.edu.cn"))
    }

    @Test
    fun `seed 后重载仍然保留 path 语义`() {
        val ctx = RuntimeEnvironment.getApplication()
        val store = SessionStore(ctx)
        store.clearCookies()

        val jar = newJar()
        jar.seed(
            "ecard.gzucm.edu.cn",
            mapOf("JSESSIONID" to "FROM_WEBVIEW", "SFP_Verify_Cookie" to "V"),
        )
        // 模拟重启：新建 jar 从磁盘恢复
        val jar2 = PersistentCookieJar(store)
        val req = jar2.loadForRequest("https://ecard.gzucm.edu.cn/user/powerfee/index".toHttpUrl())
        val js = req.firstOrNull { it.name == "JSESSIONID" }
        assertEquals("FROM_WEBVIEW", js?.value)
        assertEquals("/user", js?.path)
    }
}