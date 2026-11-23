package com.gzucm.pocket.core

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * 一次定位的结果。**带原因**，因为「没拿到」有三种完全不同的处理办法：
 * 开权限、去系统设置开定位、再试一次 —— 收敛成一句话用户只能瞎猜。
 */
sealed interface LocateResult {
    /**
     * 拿到了。
     *
     * [fromCache] 说明这是 `getLastKnownLocation` 给的、几分钟前的位置，
     * 不是刚测出来的。天气按 0.01° 取格点，几分钟的位移几乎不影响结果，
     * 所以照样可用 —— 但调用方有权知道，并决定要不要提示用户。
     */
    data class Ok(val lat: Double, val lon: Double, val fromCache: Boolean) : LocateResult

    /** 没给粗略定位权限（用户在系统弹窗上拒了，或事后去设置里关了） */
    data object NoPermission : LocateResult

    /** 系统定位总开关关着，或者系统里一个可用的 provider 都没有 */
    data object ProviderDisabled : LocateResult

    /** 权限和开关都正常、也真的问过系统了，但所有 provider 都没在预算内给出位置 */
    data object Timeout : LocateResult
}

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
 * ## 关于精度与那两个权限
 *
 * 天气按 0.01°（约 1 公里）取格点，精确到街道对结果没有任何影响，
 * 所以**功能上只用到粗略精度**。
 *
 * 但清单里同时声明了 `ACCESS_FINE_LOCATION`，原因不是精度：只声明 COARSE 时，
 * 部分国产 ROM（HyperOS/MIUI 尤其明显）的定位回调压根不触发 ——
 * 状态栏定位图标亮了、请求确实发出去了，回调却永远不来，只能等超时。
 * Android 12+ 的弹窗由用户自己选「精确 / 大致」，选大致时系统只给 COARSE
 * 精度的坐标，一切正常。判断权限时两个认一个即可。
 *
 * ## 为什么先 NETWORK 再 GPS 最后 PASSIVE
 *
 * NETWORK_PROVIDER 用基站 + WiFi，室内也能出结果、耗电低，
 * 对「大概在哪」这种需求更快。GPS 在室内可能十分钟都定不上，
 * 只在网络定位不可用时才退到它。PASSIVE 只借用别的 App 刚测到的位置，
 * 自己不发起定位，所以放最后当免费的机会。
 *
 * ## 为什么用 requestLocationUpdates 而不是 getCurrentLocation
 *
 * `getCurrentLocation`（API 30+）在国产 ROM 上不可靠：它把「等一个满足精度的
 * 定位」交给厂商实现，实测 HyperOS 上会出现状态栏定位图标亮着、回调却永远不来
 * 的情况 —— 既不返回 null 也不报错，只能等超时。`requestLocationUpdates`
 * 是十几年的老 API，各家 ROM 都得把它做对，拿第一个点就 `removeUpdates`，
 * 订阅时长只有几十毫秒到几秒，耗电可以忽略。
 *
 * ## 为什么超时是 20 秒
 *
 * 原来 10 秒在国产 ROM 上偏紧：GPS 冷启动（刚开定位、刚下飞机、
 * 地铁出站）十几秒才出第一颗星很常见，10 秒会在快要成功的时候先放弃。
 * 20 秒是「用户还愿意等」和「冷启动能出结果」之间的折中。
 *
 * ## 为什么先看 last known
 *
 * 天气要的是「大概在哪」，几分钟前的坐标完全够用，而系统缓存是**立刻**能拿到的：
 * 有新鲜缓存就没必要让用户等 20 秒。代价是拿到的是旧位置，因此结果里用
 * `fromCache` 标出来，UI 会告诉用户。
 *
 * 但缓存不能无条件优先 —— 那样用户坐高铁到了别的城市，点「使用当前位置」
 * 永远还是出发地的天气。所以只有 [CacheFreshMs] 以内的缓存才直接采信，
 * 更旧的缓存退到最后当兜底（那时实时定位已经试过 20 秒了）。
 */
object WeatherLocation {

    /** 一次定位的总预算（毫秒）。所有 provider 共享这 20 秒 */
    const val TimeoutMs: Long = 20_000L

    /**
     * 单个 provider 最多等多久。
     *
     * 不设这个上限的话，网络定位卡住会把 20 秒全吃掉，GPS 一次都试不到 ——
     * 而这恰恰是室外「只有 GPS 能定上」的场景。10 秒是原来的总预算，
     * 对单个 provider 够用，也给后面的留了机会。
     */
    private const val PerProviderMs: Long = 10_000L

    /**
     * 缓存位置「够新」的门槛。
     *
     * 半小时内换地方的可能性小（宿舍 → 教室 → 食堂），可以直接用；
     * 超过半小时就该真去定一次，否则跨城市之后天气会一直错。
     */
    private const val CacheFreshMs: Long = 30 * 60 * 1000L

    /** 只记 provider 名字和成败，坐标不进日志（坐标是隐私） */
    private const val TAG = "WeatherLocation"

    /** 询问顺序：网络 → GPS → 被动。见类注释 */
    private val ProviderOrder = listOf(
        LocationManager.NETWORK_PROVIDER,
        LocationManager.GPS_PROVIDER,
        LocationManager.PASSIVE_PROVIDER,
    )

    /**
     * 取一次坐标，返回带原因的 [LocateResult]。
     *
     * **调用方必须自己先确认粗略定位权限已授予**（这里不弹权限）——
     * 权限只在用户点「使用当前位置」那一刻申请，这个类不能有任何主动申请的动作。
     * 这里仍会检查一次，是因为系统可能在「检查完」到「真正定位」之间撤销权限
     * （用户切到系统设置关掉），那种情况要给出 [LocateResult.NoPermission] 而不是超时。
     *
     * 这是挂起函数，不会阻塞线程；但内部回调在系统给定的 Looper 上，
     * 调用方用 `withContext(Dispatchers.IO)` 包一层即可全程离开主线程。
     */
    @SuppressLint("MissingPermission")
    suspend fun current(context: Context): LocateResult {
        if (!locationGranted(context)) {
            android.util.Log.i(TAG, "失败：没有位置权限")
            return LocateResult.NoPermission
        }
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (lm == null) {
            // 极少数定制系统会没有 LocationManager，当成定位服务不可用
            android.util.Log.i(TAG, "失败：系统没有 LocationManager")
            return LocateResult.ProviderDisabled
        }

        val providers = enabledProviders(lm)
        android.util.Log.i(
            TAG,
            "enabled providers: " + providers.ifEmpty { listOf("none") }.joinToString(","),
        )
        if (providers.isEmpty()) {
            android.util.Log.i(TAG, "失败：没有可用的 provider（定位总开关可能关着）")
            return LocateResult.ProviderDisabled
        }

        // 1) 新鲜的缓存直接用：立刻有答案，不用让用户盯着等
        val cached = lastKnown(lm, providers)
        if (cached != null) {
            val age = System.currentTimeMillis() - cached.time
            if (age in 0..CacheFreshMs) {
                android.util.Log.i(TAG, "用新鲜的 last known（跳过实时定位）")
                return LocateResult.Ok(cached.latitude, cached.longitude, fromCache = true)
            }
        }

        // 2) 缓存太旧或没有：短暂订阅，拿第一个点就撤
        val live = withTimeoutOrNull(TimeoutMs) {
            for (provider in providers) {
                val fix = withTimeoutOrNull(PerProviderMs) { awaitFix(lm, provider) }
                if (fix != null) {
                    android.util.Log.i(TAG, "$provider ok（实时）")
                    return@withTimeoutOrNull fix
                }
                // awaitFix 内部已经记过具体原因，这里只说明「换下一个」
                android.util.Log.i(TAG, "$provider fail，换下一个")
            }
            null
        }
        if (live != null) {
            return LocateResult.Ok(live.latitude, live.longitude, fromCache = false)
        }

        // 3) 实时也没成：退回那条已经过期的缓存 —— 旧位置也比「拿不到」好
        if (cached != null) {
            android.util.Log.i(TAG, "实时定位没拿到，用旧缓存兜底")
            return LocateResult.Ok(cached.latitude, cached.longitude, fromCache = true)
        }

        // 实时和缓存都没有：确实尝试过了，按超时处理
        android.util.Log.i(TAG, "失败：实时和缓存都没有结果")
        return LocateResult.Timeout
    }

    /**
     * 有没有位置权限。两个认一个就算有：功能上只需要粗略精度，
     * 但用户选了「精确」时系统只标 FINE，那时没必要判成无权限。
     */
    private fun locationGranted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_FINE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED

    /**
     * 可用 provider，按 [ProviderOrder] 排。
     *
     * 特意不额外判断「这个 provider 靠不靠谱」（比如有没有缓存、有没有卫星）：
     * 只要开关是开的就试，判断交给系统，省得自己写一堆启发式规则。
     */
    private fun enabledProviders(lm: LocationManager): List<String> =
        ProviderOrder.filter { p -> runCatching { lm.isProviderEnabled(p) }.getOrDefault(false) }

    /**
     * 系统缓存里最新的一个已知位置。
     *
     * 逐个 provider 问、取 `time` 最大的那个：不同 ROM 上缓存可能只落在
     * 某一个 provider 里（比如刚开机只有 NETWORK 有值），只问一个会漏。
     * 单个 provider 抛异常（provider 不存在、权限被撤）就当它没缓存。
     */
    @SuppressLint("MissingPermission")
    private fun lastKnown(lm: LocationManager, providers: List<String>): Location? =
        providers
            .mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }
            .maxByOrNull { it.time }

    /**
     * 短暂订阅一个 provider，拿第一个点就走。失败一律回 null，具体原因只进日志。
     *
     * 用 `requestLocationUpdates` 而不是 `getCurrentLocation`：后者在部分国产 ROM
     * 上会因为「拿不到满足精度的定位」而永远不回调（见类注释）。老 API 是
     * 「每来一个点就回调一次」，第一次回调后必须 `removeUpdates`，否则订阅会一直活着
     * 往后台耗电 —— 超时和协程取消也都要撤。
     *
     * `minTime = 0` / `minDistance = 0f`：只关心「尽快给一个点」，
     * 得到后立刻退订，所以不需要节流。
     *
     * [AtomicBoolean] 是因为个别 ROM 会在 `removeUpdates` 生效前再补一条回调，
     * 协程重复 resume 会直接抛 IllegalStateException。
     */
    @SuppressLint("MissingPermission")
    private suspend fun awaitFix(lm: LocationManager, provider: String): Location? =
        suspendCancellableCoroutine { cont ->
            val finished = AtomicBoolean(false)

            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    if (!finished.compareAndSet(false, true)) return
                    // 一次就够，先撤再交结果，避免多一条回调打进来
                    runCatching { lm.removeUpdates(this) }
                    if (cont.isActive) cont.resume(location)
                }

                override fun onProviderDisabled(provider: String) {
                    // 定位中途被用户关掉：当成这次失败，交给下一个 provider
                    if (!finished.compareAndSet(false, true)) return
                    android.util.Log.i(TAG, "$provider 定位途中被关闭")
                    runCatching { lm.removeUpdates(this) }
                    if (cont.isActive) cont.resume(null)
                }

                // 这两个在接口里是必实现项，用不上
                override fun onProviderEnabled(provider: String) = Unit
                @Deprecated("API 29 起废弃，但 26–28 必须实现")
                override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit
            }

            try {
                lm.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
                // 超时 / 页面被销毁 → 协程取消 → 必须撤掉订阅，否则它会一直等下去
                cont.invokeOnCancellation { runCatching { lm.removeUpdates(listener) } }
            } catch (e: SecurityException) {
                // 权限在这一刻被撤销（用户去系统设置关了）
                android.util.Log.i(TAG, "$provider 抛 SecurityException")
                if (finished.compareAndSet(false, true) && cont.isActive) cont.resume(null)
            } catch (e: Exception) {
                // provider 不存在、系统定位服务死了 —— 都只影响这一次
                android.util.Log.i(TAG, "$provider 抛 ${e.javaClass.simpleName}")
                if (finished.compareAndSet(false, true) && cont.isActive) cont.resume(null)
            }
        }
}
