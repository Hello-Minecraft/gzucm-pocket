package com.gzucm.pocket.core

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * 取一次当前位置，只取一次。
 *
 * ## 为什么不用 Google Play Services（FusedLocationProviderClient）
 *
 * 同学的手机大多是国行华为 / 荣耀，很多机器根本没有 GMS：
 * 没有 Play 服务时 Fused 要么直接抛异常，要么永远不回回调。
 * 天气要的精度低到「只要知道在广州还是深圳」，系统自带的
 * [LocationManager] 完全够用，还少一个几十 MB 的依赖。
 *
 * ## 为什么只要粗略定位
 *
 * 天气按 0.01°（约 1 公里）取格点，精确到街道对结果没有任何影响。
 * 但精确权限在系统弹窗上写的是「精确位置」，用户会警觉 ——
 * 为一个天气卡要这种权限，不值。所以清单里**只**声明
 * `ACCESS_COARSE_LOCATION`，这里也只判断这一个权限。
 *
 * ## 为什么先 NETWORK 再 GPS
 *
 * NETWORK_PROVIDER 用基站 + WiFi，室内也能出结果、耗电低，
 * 对「大概在哪」这种需求更快。GPS 在室内可能十分钟都定不上，
 * 只在网络定位不可用时才退到它。
 *
 * ## 超时 10 秒
 *
 * 用户点完「使用当前位置」就盯着屏幕了。10 秒拿不到就当失败，
 * 提示换预设 —— 比转圈半分钟再失败要好。超时靠 [withTimeoutOrNull]，
 * 协程一取消，两个分支都会把注册的回调撤掉（见里面 `invokeOnCancellation`），
 * 不会留下一个还活着的定位请求。
 */
object WeatherLocation {

    /** 一次定位的总预算（毫秒）。所有 provider 共享这 10 秒 */
    const val TimeoutMs: Long = 10_000L

    /**
     * 取一次坐标。拿不到返回 null（没权限、定位关了、超时、系统没有 provider）。
     *
     * **调用方必须自己先确认粗略定位权限已授予**（这里不弹权限、也不检查）——
     * 权限只在用户点「使用当前位置」那一刻申请，这个类不能有任何主动申请的动作。
     *
     * 这是挂起函数，不会阻塞线程；但内部回调在系统给定的 Looper 上，
     * 调用方用 `withContext(Dispatchers.IO)` 包一层即可全程离开主线程。
     */
    @SuppressLint("MissingPermission")
    suspend fun current(context: Context): Location? {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return null
        val providers = enabledProviders(lm)
        if (providers.isEmpty()) return null
        return withTimeoutOrNull(TimeoutMs) {
            for (provider in providers) {
                // 单个 provider 拿不到（比如网络定位服务被关）就退到下一个
                val fix = awaitFix(context, lm, provider) ?: continue
                return@withTimeoutOrNull fix
            }
            null
        }
    }

    /**
     * 可用 provider，网络在前。
     *
     * 特意不检查 `isProviderEnabled(GPS)` 之外的任何东西（比如是否有过缓存位置）：
     * 只要 provider 开着就试，省得自己判断「这个 provider 靠不靠谱」。
     */
    private fun enabledProviders(lm: LocationManager): List<String> =
        listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .filter { p -> runCatching { lm.isProviderEnabled(p) }.getOrDefault(false) }

    /**
     * 等一个 provider 给出结果。
     *
     * API 30+ 用 `getCurrentLocation`（系统自己处理「等一个新鲜定位」，
     * 不会把半小时前的缓存位置当结果）；26–29 只有 `requestSingleUpdate`，
     * 拿到一次必须 `removeUpdates`，否则回调会一直挂着。
     *
     * [AtomicBoolean] 是因为个别 ROM（尤其是国产 ROM 的定位服务）会在
     * 一次请求里回调两次，协程重复 resume 会直接抛 IllegalStateException。
     */
    @SuppressLint("MissingPermission")
    private suspend fun awaitFix(
        context: Context,
        lm: LocationManager,
        provider: String,
    ): Location? = suspendCancellableCoroutine { cont ->
        val finished = AtomicBoolean(false)

        fun finish(location: Location?) {
            if (!finished.compareAndSet(false, true)) return
            if (cont.isActive) cont.resume(location)
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // 回调走主线程的 Executor：不阻塞，也不占一个后台线程等定位
                lm.getCurrentLocation(
                    provider,
                    null,
                    ContextCompat.getMainExecutor(context),
                ) { location -> finish(location) }
            } else {
                @Suppress("DEPRECATION")
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        // 一次就够，先撤再交结果，避免多一条回调打进来
                        runCatching { lm.removeUpdates(this) }
                        finish(location)
                    }

                    override fun onProviderDisabled(provider: String) {
                        // 定位中途被用户关掉：当成这次失败，交给下一个 provider
                        runCatching { lm.removeUpdates(this) }
                        finish(null)
                    }

                    // 这两个在 API 26–29 的接口里是必实现项，用不上
                    override fun onProviderEnabled(provider: String) = Unit
                    @Deprecated("API 29 起废弃，但 26–28 必须实现")
                    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit
                }
                @Suppress("DEPRECATION")
                lm.requestSingleUpdate(provider, listener, Looper.getMainLooper())
                // 超时 / 页面被销毁 → 协程取消 → 必须撤掉监听，否则它会一直等到下次定位
                cont.invokeOnCancellation { runCatching { lm.removeUpdates(listener) } }
            }
        } catch (e: SecurityException) {
            // 权限在这一刻被撤销（用户去系统设置关了）
            finish(null)
        } catch (e: Exception) {
            // provider 不存在、系统定位服务死了 —— 都只影响这一次
            finish(null)
        }
    }
}
