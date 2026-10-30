package com.gzucm.pocket

import com.gzucm.pocket.core.NetError
import com.gzucm.pocket.core.UserMessage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

/**
 * 界面上的错误文案。
 *
 * 硬性要求：**不能出现英文类名或原始异常消息**。
 * 之前登录过期会跳出一串 `LoginRequiredException: HTTP 401 ...`，很难看。
 * 这里逐个类型断言「有中文、没英文关键词」，加了新异常类型也会被兜底规则覆盖。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UserMessageTest {

    /** 一条给用户看的消息不该带这些 */
    private fun assertClean(msg: String) {
        assertTrue("不能为空", msg.isNotBlank())
        listOf("Exception", "HTTP", "java.", "null", "code=").forEach {
            assertFalse("不该包含 $it：$msg", msg.contains(it))
        }
        // 至少有一个汉字
        assertTrue("必须是中文：$msg", msg.any { it.code in 0x4E00..0x9FFF })
    }

    @Test
    fun `登录失效说人话`() {
        val m = UserMessage.of(NetError.Unauthorized(401))
        assertClean(m)
        assertTrue(m.contains("登录"))
    }

    @Test
    fun `http 401 也算登录失效`() {
        assertTrue(UserMessage.isAuthIssue(NetError.Http(401, "")))
        assertTrue(UserMessage.isAuthIssue(NetError.Http(403, "")))
        assertTrue(UserMessage.isAuthIssue(NetError.Unauthorized(200)))
    }

    @Test
    fun `http 500 不算登录问题`() {
        val m = UserMessage.of(NetError.Http(500, "<html>error</html>"))
        assertClean(m)
        assertFalse(UserMessage.isAuthIssue(NetError.Http(500, "")))
        assertTrue(m.contains("服务器"))
    }

    @Test
    fun `网络不可达有中文提示`() {
        assertClean(UserMessage.of(NetError.Unreachable(UnknownHostException("jw.gzucm.edu.cn"))))
    }

    @Test
    fun `超时`() {
        val m = UserMessage.of(SocketTimeoutException("timeout"))
        assertClean(m)
        assertTrue(m.contains("超时"))
    }

    @Test
    fun `tls 握手失败`() {
        assertClean(UserMessage.of(SSLHandshakeException("handshake failed")))
    }

    @Test
    fun `未知异常走兜底 不吐类名`() {
        val m = UserMessage.of(IllegalStateException("something weird"))
        assertClean(m)
        assertFalse(m.contains("IllegalState"))
    }

    @Test
    fun `纯 io 异常也不吐英文`() {
        assertClean(UserMessage.of(IOException("connection reset by peer")))
    }
}
