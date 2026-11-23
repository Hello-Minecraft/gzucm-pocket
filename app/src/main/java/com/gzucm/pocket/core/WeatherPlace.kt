package com.gzucm.pocket.core

import android.content.Context

/**
 * 天气要看的那个地方。
 *
 * ## 为什么坐标写死在代码里
 *
 * 四个预设都是「用户真正会待着的地方」——两个校区、两家实习医院。
 * 写死的好处是：点一下就出天气，不用等定位、不申请任何权限，
 * 坐标也不会因为手机定位漂移而每天变一次。
 *
 * ## 「当前位置」也走同一个类型
 *
 * 定位拿到的不外乎一组经纬度，和预设没有本质区别，所以 [ofCurrent]
 * 造出来的也是 [WeatherPlace]，只是 id 固定成 [CurrentId]。
 * 这样上层（天气请求、设置页勾选、SharedPreferences）只有一条路。
 *
 * 坐标两位小数就够：和风按 0.01° 取格点（约 1 公里），
 * 多给的位数一个都不会被用上，存下来反而像是「我们在精确追踪你」。
 */
data class WeatherPlace(
    /** `sanyuanli` / `daxuecheng` / `baoan` / `sz` / `current` */
    val id: String,
    /** 显示名。天气卡标题和设置页都用它 */
    val label: String,
    val lat: Double,
    val lon: Double,
) {
    /** 是不是定位取来的（用来决定设置页显示坐标而不是「预设」） */
    val isCurrent: Boolean get() = id == CurrentId

    companion object {
        /** 「当前位置」的固定 id。坐标每次定位都会变，靠 id 认人 */
        const val CurrentId = "current"

        /** 反查不到城市时的兜底名。坐标反查要额外接一个服务，这里不做 */
        const val CurrentLabel = "当前位置"

        /**
         * 预设地点。**顺序就是设置页里的顺序**，第一个是默认值。
         *
         * 默认必须还是三元里：老用户升级上来，天气卡一个字都不该变。
         */
        val presets: List<WeatherPlace> = listOf(
            WeatherPlace("sanyuanli", "广中医三元里校区", 23.16, 113.26),
            WeatherPlace("daxuecheng", "广中医大学城校区", 23.05, 113.39),
            WeatherPlace("baoan", "深圳市宝安区中医院", 22.56, 113.88),
            WeatherPlace("sz", "深圳市中医院", 22.54, 114.07),
        )

        val default: WeatherPlace = presets.first()

        /**
         * 用定位结果造一个「当前位置」。
         *
         * 先抹到两位小数再存：见类注释，和风只用得到这一位精度。
         * 取整用 [Math.round] 而不是 `toString().take(n)`，避免出现
         * 「113.2 写成 113.20 又被读成 113.2」这类字符串截断的坑。
         */
        fun ofCurrent(lat: Double, lon: Double): WeatherPlace = WeatherPlace(
            id = CurrentId,
            label = CurrentLabel,
            lat = round2(lat),
            lon = round2(lon),
        )

        private fun round2(v: Double): Double = Math.round(v * 100.0) / 100.0

        /** 按 id 找预设；找不到返回 null（`current` 也会返回 null） */
        fun presetOf(id: String): WeatherPlace? = presets.firstOrNull { it.id == id }
    }
}

/**
 * 天气地点的持久化。写法照 [SessionStore]：同一个 SharedPreferences 文件、
 * 一个属性读完写。
 *
 * ## 为什么只有本机
 *
 * 坐标本身就是「人在哪」的直接证据，比任何浏览记录都敏感。
 * 所以它只进本机 SharedPreferences：不上传、不写日志、不进云端备份之外的任何地方。
 * 发出去的只有天气请求里的 `lat`/`lon` 两个数字 —— 那是取天气必须给的。
 *
 * ## 为什么不存整个对象 JSON
 *
 * 四个字段分别存，坏了一个不至于整条读不出来；而且 [place] 的读取逻辑里
 * 有一层「存的 id 是预设就返回预设本身」的校正 —— 以后改预设坐标，
 * 老用户不用手动重新选一次。
 */
class WeatherPlaceStore(context: Context) {

    private val sp = context.getSharedPreferences("gzucm_pocket", Context.MODE_PRIVATE)

    var place: WeatherPlace
        get() {
            val id = sp.getString(KEY_ID, null) ?: return WeatherPlace.default
            // 预设：直接给常量里那份（label/坐标都以代码为准，改预设能立刻生效）
            WeatherPlace.presetOf(id)?.let { return it }
            // 自定义（当前位置）：必须存过坐标
            val lat = sp.getString(KEY_LAT, null)?.toDoubleOrNull() ?: return WeatherPlace.default
            val lon = sp.getString(KEY_LON, null)?.toDoubleOrNull() ?: return WeatherPlace.default
            // 坐标存坏了（手改、迁移）就回默认，宁可见广州也不要一个 (0,0)
            if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return WeatherPlace.default
            val label = sp.getString(KEY_LABEL, null)?.takeIf { it.isNotBlank() }
                ?: WeatherPlace.CurrentLabel
            return WeatherPlace(id, label, lat, lon)
        }
        set(v) = sp.edit()
            .putString(KEY_ID, v.id)
            .putString(KEY_LABEL, v.label)
            .putString(KEY_LAT, v.lat.toString())
            .putString(KEY_LON, v.lon.toString())
            .apply()

    private companion object {
        const val KEY_ID = "weather_place_id"
        const val KEY_LABEL = "weather_place_label"
        const val KEY_LAT = "weather_place_lat"
        const val KEY_LON = "weather_place_lon"
    }
}
