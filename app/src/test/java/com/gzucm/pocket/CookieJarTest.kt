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

    // ── 2026-11-06：线程安全 / 删除语义 / 排序 ───────────────────────────

    @Test
    fun `多线程同时读写不抛异常`() {
        // 课表是 Semaphore(5) 五路并发请求；原来 jar 用普通 HashMap，
        // 一边 save 一边遍历会抛 ConcurrentModificationException，被上层吞掉后丢一周的课
        /*
         * 读写分开两组线程，并且每次写入**新增**键（LinkedHashMap 只有结构性修改，
         * 也就是新增/删除键，才会让正在遍历的迭代器抛 CME；改已有键的值不会）。
         * 最早版本的测试是同一线程「写一次读一次」、键循环复用，
         * 去掉锁也照样通过 —— 抓不住 bug，等于没测。
         */
        val jar = newJar()
        val url = "https://jw.gzucm.edu.cn/new/student/xsgrkb/getCalendarWeekDatas".toHttpUrl()
        val errors = java.util.concurrent.atomic.AtomicInteger(0)
        val firstError = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
        val stop = java.util.concurrent.atomic.AtomicBoolean(false)
        val start = java.util.concurrent.CountDownLatch(1)
        val writers = 4
        val readers = 4
        val pool = java.util.concurrent.Executors.newFixedThreadPool(writers + readers)
        val done = java.util.concurrent.CountDownLatch(writers + readers)

        repeat(writers) { t ->
            pool.execute {
                try {
                    start.await()
                    var i = 0
                    while (!stop.get() && i < 4000) {
                        jar.saveFromResponse(
                            url,
                            listOf(
                                Cookie.Builder().domain("jw.gzucm.edu.cn").path("/")
                                    .name("W${t}_$i").value("v").build(),
                            ),
                        )
                        i++
                    }
                } catch (e: Throwable) {
                    errors.incrementAndGet(); firstError.compareAndSet(null, e)
                } finally {
                    done.countDown()
                }
            }
        }
        repeat(readers) {
            pool.execute {
                try {
                    start.await()
                    repeat(4000) { if (!stop.get()) jar.loadForRequest(url) }
                } catch (e: Throwable) {
                    errors.incrementAndGet(); firstError.compareAndSet(null, e)
                } finally {
                    done.countDown()
                }
            }
        }
        start.countDown()
        val finished = done.await(60, java.util.concurrent.TimeUnit.SECONDS)
        stop.set(true)
        pool.shutdownNow()
        assertTrue("60 秒内没跑完（写盘太慢？）", finished)
        assertEquals("并发读写出现异常：${firstError.get()}", 0, errors.get())
        // 4 个写线程各写了 4000 个不同的键，一个都不能丢
        assertEquals(writers * 4000, jar.loadForRequest(url).size)

        // 合并写盘之后磁盘上也要是全的
        jar.flushNow()
        val reloaded = PersistentCookieJar(SessionStore(RuntimeEnvironment.getApplication()))
        assertEquals(writers * 4000, reloaded.loadForRequest(url).size)
    }

    @Test
    fun `服务端用过期时间删除 cookie 时 jar 真的删掉`() {
        val jar = newJar()
        val url = "https://jw.gzucm.edu.cn/new/index".toHttpUrl()
        jar.saveFromResponse(
            url,
            listOf(Cookie.Builder().domain("jw.gzucm.edu.cn").path("/").name("JSESSIONID").value("OLD").build()),
        )
        // Max-Age=0 → OkHttp 给出过去的 expiresAt
        jar.saveFromResponse(
            url,
            listOf(
                Cookie.Builder().domain("jw.gzucm.edu.cn").path("/").name("JSESSIONID").value("")
                    .expiresAt(System.currentTimeMillis() - 1_000).build(),
            ),
        )
        val sent = jar.loadForRequest(url)
        assertTrue("被删的 cookie 不该再发出去：$sent", sent.none { it.name == "JSESSIONID" })
    }

    @Test
    fun `saveFromResponse 合并写盘后重启仍能读到`() {
        val ctx = RuntimeEnvironment.getApplication()
        val jar = newJar()
        val url = "https://jw.gzucm.edu.cn/new/index".toHttpUrl()
        jar.saveFromResponse(
            url,
            listOf(Cookie.Builder().domain("jw.gzucm.edu.cn").path("/").name("JSESSIONID").value("S1").build()),
        )
        jar.flushNow()
        val reloaded = PersistentCookieJar(SessionStore(ctx))
        assertEquals("S1", reloaded.loadForRequest(url).firstOrNull { it.name == "JSESSIONID" }?.value)
    }

    @Test
    fun `clear 之后待写盘的 cookie 不会被写回`() {
        val ctx = RuntimeEnvironment.getApplication()
        val jar = newJar()
        val url = "https://jw.gzucm.edu.cn/new/index".toHttpUrl()
        jar.saveFromResponse(
            url,
            listOf(Cookie.Builder().domain("jw.gzucm.edu.cn").path("/").name("JSESSIONID").value("S1").build()),
        )
        // 还没到 300ms 就登出
        jar.clear()
        jar.flushNow()
        Thread.sleep(400) // 等计划中的那次 flush 也跑完
        val reloaded = PersistentCookieJar(SessionStore(ctx))
        assertTrue("登出后磁盘上不该还有会话", !reloaded.has("jw.gzucm.edu.cn"))
    }

    @Test
    fun `同名 cookie 按 path 长度从长到短发送`() {
        val jar = newJar()
        jar.put("ecard.gzucm.edu.cn", "JSESSIONID", "ROOT", "/")
        jar.put("ecard.gzucm.edu.cn", "JSESSIONID", "USER", "/user")
        val sent = jar.loadForRequest("https://ecard.gzucm.edu.cn/user/powerfee/index".toHttpUrl())
            .filter { it.name == "JSESSIONID" }
        assertEquals(listOf("USER", "ROOT"), sent.map { it.value })
    }
}