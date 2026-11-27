package com.gzucm.pocket.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.gzucm.pocket.core.LocateResult
import com.gzucm.pocket.core.WeatherLocation
import com.gzucm.pocket.core.WeatherPlace
import com.gzucm.pocket.ui.components.G3CornerShape
import com.gzucm.pocket.ui.components.micaPanel
import com.gzucm.pocket.ui.icons.AppIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * 「天气位置」子页面。设置面板里替换正文用，和「今日页显示」「关于软件」同一层。
 *
 * ## 隐私相关的三条硬约束（改这个文件前先读一遍）
 *
 * 1. **权限只在点这一行时申请**。这个函数里 `askLocation.launch` 只出现在
 *    「使用当前位置」的点击回调里。启动、进入设置页、渲染这个列表，全都不申请、
 *    不检查（`checkSelfPermission` 也只在用户点了之后才调）。
 * 2. **精度仍然只要粗略的**。天气只要知道「在广州还是深圳」，精确到宿舍隔壁
 *    那条街没有任何用，反而更敏感。清单里同时声明了 `ACCESS_FINE_LOCATION`，
 *    但那是为了让国产 ROM 的定位回调能回来（见 AndroidManifest 注释，
 *    只声明 COARSE 时 HyperOS 上会出现「回调永远不来」），不是要提高精度；
 *    判定通过与否也只看 COARSE。
 * 3. **坐标不出本机**。存进 SharedPreferences 后只作为天气请求的 `lat`/`lon`
 *    发出去（那是取天气必须给的），不写日志、不上传别处。
 *
 * ## 失败不崩
 *
 * 定位超时、GPS 关着、系统没有定位服务、权限被拒 —— 所有分支都只是把
 * [error] 置成一句人话，列表照常可用，用户可以随手换一个预设。
 */
@Composable
fun WeatherPlaceContent(
    current: WeatherPlace,
    /** 选中后：存盘 + 重拉天气 + 关掉子页面，都由上层做 */
    onPick: (WeatherPlace) -> Unit,
    onBack: () -> Unit,
    /** 左上角返回文案。从设置进来是「返回设置」，从今日页进来是「返回」 */
    backLabel: String = "返回设置",
) {
    val scheme = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    // 正在等定位。这期间点别的不响应，免得同时发两个定位请求
    var locating by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    /**
     * 只在用户点过之后才用得上的判断，**不要**放到启动路径里。
     *
     * 两个权限认一个就算通过：功能上只需要 COARSE，但 FINE 单独被授予
     * （或系统只回了 FINE）时没必要再弹一次窗 —— 系统给 FINE 必然连带 COARSE。
     */
    fun locationGranted(): Boolean =
        ContextCompat.checkSelfPermission(
            ctx, Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                ctx, Manifest.permission.ACCESS_FINE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED

    // 定位：挂起函数，拿到坐标才回来。Dispatchers.IO 让整段离开主线程，
    // 回调本身仍然走系统给的主线程 Executor（不阻塞）
    fun startLocate() {
        if (locating) return
        locating = true
        error = null
        scope.launch {
            val result = withContext(Dispatchers.IO) { WeatherLocation.current(ctx) }
            locating = false
            /*
             * 按原因给不同的话：三种失败要用户做的事完全不一样
             * （去开权限 / 去开系统定位 / 原地再试一次），
             * 收敛成「没拿到位置」等于让用户自己猜。
             */
            when (result) {
                is LocateResult.Ok -> {
                    /*
                     * 缓存位置（几分钟前的）照样用，但要告诉用户一声：
                     * 否则「人明明到深圳了天气还是广州」会被当成 bug。
                     * 这一页紧接着就会被上层关掉，没法在页内显示，用 Toast。
                     */
                    if (result.fromCache) {
                        Toast.makeText(
                            ctx, "刚才没定上，用的是上次定位的位置", Toast.LENGTH_SHORT,
                        ).show()
                    }
                    onPick(WeatherPlace.ofCurrent(result.lat, result.lon))
                }

                LocateResult.NoPermission ->
                    error = "没给定位权限。只在用这个功能时才需要它，不给也能选上面的预设"

                LocateResult.ProviderDisabled ->
                    error = "手机的定位服务是关着的，去系统设置里打开「位置信息」再回来试"

                LocateResult.Timeout ->
                    error = "定位超时了。室内或刚开定位时容易这样，再试一次或者选预设"
            }
        }
    }

    /*
     * 权限结果回来：两个权限认一个就定位（功能上只要 COARSE 精度）。
     * 拒绝就只提示 —— 不弹「去设置开启」的对话框，也不反复申请，
     * 用户不想要定位这件事本身是合理的，预设地点照样能用。
     *
     * 请求里带 FINE 不是为了提高精度，而是因为只声明 COARSE 时部分国产 ROM
     * 的定位回调压根不触发（见 AndroidManifest 里的注释）；Android 12+ 的弹窗
     * 由用户自己选「精确 / 大致」，选大致也算通过。
     */
    val askLocation = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        // 两个认一个就算通过（功能上只需要 COARSE，FINE 被拒也能用）
        val ok = granted[Manifest.permission.ACCESS_COARSE_LOCATION] == true ||
            granted[Manifest.permission.ACCESS_FINE_LOCATION] == true
        if (ok) startLocate()
        else error = "没给定位权限。只在用这个功能时才需要它，不给也能选上面的预设"
    }

    Column(
        Modifier
            .fillMaxWidth()
            /*
             * 五个预设 + 一行「当前位置」+ 错误提示，小屏（尤其开着大字体）
             * 一屏放不下。加滚动而不是砍行数或写死高度：预设数量以后还会变。
             */
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 26.dp),
    ) {
        Row(
            Modifier
                .padding(bottom = 8.dp)
                .clip(G3CornerShape(10.dp))
                .clickable(onClick = onBack)
                .padding(vertical = 6.dp, horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                AppIcons.ArrowBack,
                contentDescription = null,
                modifier = Modifier.size(17.dp),
                tint = scheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(5.dp))
            Text(backLabel, fontSize = 12.5.sp, color = scheme.onSurfaceVariant)
        }

        Text(
            "天气位置",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.height(3.dp))
        Text(
            "天气卡显示哪个地方的天气。选好之后首页会立刻重新拉一次。",
            fontSize = 12.5.sp,
            lineHeight = 18.sp,
            color = scheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))

        Column(Modifier.fillMaxWidth().micaPanel(14.dp)) {
            WeatherPlace.presets.forEach { place ->
                PlaceRow(
                    title = place.label,
                    subtitle = null,
                    selected = !current.isCurrent && current.id == place.id,
                    enabled = !locating,
                ) { onPick(place) }
                PlaceDivider()
            }

            /*
             * 最后一行「使用当前位置」。
             *
             * 这一行的点击是**唯一的权限入口**：没有权限时才 launch 请求，
             * 已经授权就直接定位（不再弹一次窗，系统也不会弹）。
             */
            PlaceRow(
                title = WeatherPlace.CurrentLabel,
                subtitle = if (current.isCurrent) {
                    "已使用定位：${coordText(current.lat)}, ${coordText(current.lon)}"
                } else {
                    // 不再写「只申请粗略定位」：清单里 COARSE/FINE 都声明了，
                    // 系统弹窗会问一次，选「大致位置」就够用
                    "用手机定位取一次坐标，系统会问一次位置权限"
                },
                selected = current.isCurrent,
                enabled = !locating,
                trailing = if (locating) "定位中…" else null,
            ) {
                if (locationGranted()) startLocate()
                else askLocation.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                        Manifest.permission.ACCESS_FINE_LOCATION,
                    )
                )
            }
        }

        if (error != null) {
            Spacer(Modifier.height(10.dp))
            Text(
                error.orEmpty(),
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = scheme.error,
            )
        }

        Spacer(Modifier.height(14.dp))

        /*
         * 和设置页那一行下面的说明同一个意思，这里再说一遍是因为
         * 这一页才是用户真正做选择的地方。
         */
        Text(
            "仅用于获取你所在位置的天气。坐标只保存在这台手机上，不会上传，我们服务器也不保存。",
            fontSize = 11.5.sp,
            lineHeight = 17.sp,
            color = scheme.onSurfaceVariant.copy(alpha = 0.85f),
        )
    }
}

/** 坐标显示：两位小数，和请求里发给服务端的精度一致 */
private fun coordText(v: Double): String = String.format(Locale.US, "%.2f", v)

@Composable
private fun PlaceDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp)
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
    )
}

/**
 * 一行地点。选中时右侧一个勾（和系统单选列表的习惯一致）。
 *
 * [trailing] 只在「定位中…」时用：这一行不能换成一个转圈，
 * 否则列表高度会跳一下，用户刚点的那一行会跑掉。
 */
@Composable
private fun PlaceRow(
    title: String,
    subtitle: String?,
    selected: Boolean,
    enabled: Boolean,
    trailing: String? = null,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .clip(G3CornerShape(10.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                fontSize = 13.5.sp,
                color = if (selected) scheme.primary else scheme.onSurface,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            )
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    color = scheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        when {
            trailing != null -> Text(trailing, fontSize = 11.5.sp, color = scheme.primary)
            selected -> Icon(
                AppIcons.Check,
                contentDescription = "已选中",
                modifier = Modifier.size(18.dp),
                tint = scheme.primary,
            )
        }
    }
}
