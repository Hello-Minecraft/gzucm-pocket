package com.gzucm.pocket.core

import android.util.Log
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * 把异常翻译成**给人看的一句话**。
 *
 * 之前界面直接显示 `e.message`，登录过期时会跳出一串
 * `LoginRequiredException: HTTP 401 ...` 之类的英文，很难看也不解决问题。
 * 现在界面上只出现中文，而且每条都告诉用户「接下来该怎么办」；
 * 原始信息一律进 logcat，排查时用 logcat 看。
 */
object UserMessage {

    fun of(e: Throwable): String {
        // 原始信息留给排查，不上界面
        Log.w("GzucmPocket", "error: ${e.javaClass.name} / ${e.message}", e)

        return when (e) {
            is NetError.Unauthorized -> "登录已过期，重新登录一下就行"
            is NetError.Unreachable -> "连不上学校服务器，检查网络后重试"
            is NetError.Parse -> "学校返回的数据格式变了，暂时读不出来"
            is NetError.Http -> when (e.code) {
                401, 403 -> "登录已过期，重新登录一下就行"
                in 500..599 -> "学校服务器出故障了，过会儿再试"
                else -> "学校服务器返回异常（${e.code}）"
            }

            is SocketTimeoutException -> "网络有点慢，连接超时了"
            is UnknownHostException, is ConnectException -> "连不上学校服务器，检查网络后重试"
            is SSLException -> "网络连接被拦截了，换个网络试试"
            is IOException -> "网络不稳定，稍后再试"

            // 兜底：实际遇到的「说不清原因」几乎都是会话掉了
            // （返回登录页 HTML → 解析失败、或学校侧悄悄改鉴权），
            // 与其说「出了点问题」让用户干瞪眼，不如直接指条明路
            else -> "登录状态掉了，请重新登录"
        }
    }

    /** 这条错误是不是「需要用户重新登录」 */
    fun isAuthIssue(e: Throwable): Boolean = when (e) {
        is NetError.Unauthorized -> true
        is NetError.Http -> e.code == 401 || e.code == 403
        else -> false
    }
}
