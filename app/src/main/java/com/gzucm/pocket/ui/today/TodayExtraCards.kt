package com.gzucm.pocket.ui.today

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gzucm.pocket.R
import com.gzucm.pocket.core.AgendaOccurrence
import com.gzucm.pocket.core.EXAM_COLOR_INDEX
import com.gzucm.pocket.core.Weather
import com.gzucm.pocket.ui.components.G3CornerShape
import com.gzucm.pocket.ui.components.micaPanel
import com.gzucm.pocket.ui.icons.AppIcons
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * 首页的天气卡。
 *
 * ## 为什么是「独立卡」而不是标题旁的一行小字
 *
 * 致辉 2026-11-20 选的是独立云母卡：他现在每天出门前要单独开一次天气 App，
 * 这张卡的价值是「扫一眼就走」。一行小字在视觉上太弱，会被当成装饰。
 *
 * ## 数据只有一份，呈现两处
 *
 * 上半是「现在」：温度、天气、体感、湿度；下半是未来三天的高低温和图形。
 * 三天刚好是「要不要带伞 / 周末冷不冷」的量，再多就变成第二张电费卡了。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WeatherCard(
    info: Weather.Info?,
    refreshing: Boolean,
    error: String?,
    onLongPress: () -> Unit,
    /**
     * 没有数据时标题上写的地点。
     *
     * 有数据时用 [Weather.Info.city]（拉取时按用户选的地点写进去的）——
     * 两者其实同源，只是在「正在拉 / 拉失败」这几秒里 info 还是空的，
     * 那时候也得让用户看见「这是哪儿的天气」。
     */
    placeLabel: String = Weather.CityLabel,
    /**
     * 点地点那一小块：进「天气位置」选择页。
     *
     * 整卡长按是「打开今日页显示管理」，这里只是卡内左上角那一小块，
     * 热区比文字略大一圈（见下面的 padding），两件事互不干扰：
     * 点地点 = 换城市；长按地点或卡内其它地方 = 管理区块。
     */
    onPlaceClick: () -> Unit = {},
) {
    val scheme = MaterialTheme.colorScheme

    /*
     * 没有任何数据时也占一张卡：直接消失的话，用户会以为开关坏了。
     * 卡里写清「为什么没有」—— 没 key、没网、还是正在拉，三种情形文案不同。
     */
    val emptyText = when {
        refreshing -> "正在获取天气…"
        error != null -> error
        else -> "天气暂时没有数据"
    }

    Column(
        Modifier
            .fillMaxWidth()
            .micaPanel(18.dp)
            .cardLongPress(onLongPress)
            .padding(horizontal = 17.dp, vertical = 15.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            /*
             * 地点 + ›。整块可点（进天气位置），热区靠 1dp 横向 / 2dp 纵向
             * padding 撑开一点点，视觉上看不出来，手指按得住。
             *
             * 用 combinedClickable 而不是 clickable：外层 cardLongPress 也是
             * detectTapGestures 系，内层 clickable 会在 Main pass 把 down 消费掉，
             * 外层 requireUnconsumed 拿不到就跳过，长按这一小块会变成死区。
             */
            Row(
                Modifier
                    .clip(G3CornerShape(6.dp))
                    .combinedClickable(
                        onClick = onPlaceClick,
                        onLongClick = onLongPress,
                    )
                    .padding(vertical = 2.dp, horizontal = 1.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    // 地点由用户在设置里选，不再是写死的「广州 · 三元里」
                    info?.city ?: placeLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(3.dp))
                Icon(
                    AppIcons.ChevronRight,
                    contentDescription = null,
                    modifier = Modifier.size(11.dp),
                    tint = scheme.onSurfaceVariant.copy(alpha = 0.45f),
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                when {
                    info == null -> ""
                    refreshing -> "更新中"
                    else -> ageLabel(info.fetchedAt)
                },
                fontSize = 10.5.sp,
                color = scheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
        }

        if (info == null) {
            Spacer(Modifier.height(10.dp))
            Text(
                emptyText,
                fontSize = 13.sp,
                color = scheme.onSurfaceVariant,
            )
            return@Column
        }

        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${info.temp}°",
                fontSize = 40.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = (-1.5).sp,
                color = scheme.onSurface,
            )
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    info.text,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = scheme.onSurface,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "体感 ${info.feelsLike}° · 湿度 ${info.humidity}%",
                    fontSize = 11.sp,
                    color = scheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.weight(1f))
            WeatherGlyph(
                code = info.icon,
                size = 46.dp,
                tint = scheme.primary,
            )
        }

        if (info.days.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            /*
             * 未来三天。第一行永远是今天 —— 和风 7d 的第一条就是今天，
             * 但今天的最高最低和上面的「现在 14°」不是一回事（一个是当前、一个是全天），
             * 所以标签写「今天」而不是省掉。
             */
            val today = LocalDate.now()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                info.days.take(3).forEach { d ->
                    Column(
                        Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            dayLabel(d.date, today),
                            fontSize = 10.5.sp,
                            color = scheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(4.dp))
                        WeatherGlyph(
                            code = d.icon,
                            size = 20.dp,
                            tint = scheme.onSurfaceVariant.copy(alpha = 0.85f),
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "${d.min}~${d.max}°",
                            fontSize = 11.sp,
                            color = scheme.onSurface.copy(alpha = 0.85f),
                        )
                    }
                }
            }
        }
    }
}

/**
 * 「接下来 N 天」。
 *
 * 只显示今天**之后**的日程 / 考试：今天的内容上面已经有一整段了，
 * 这里再出现一遍会让「今天」和「未来」混在一起。
 *
 * 最多 5 条（[com.gzucm.pocket.core.TodayLogic.MaxUpcoming]），
 * 多出来的用一行「还有 N 条」收口 —— 它是个提醒，不是第二张日程表。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun UpcomingCard(
    items: List<AgendaOccurrence>,
    days: Int,
    onAgendaClick: (AgendaOccurrence) -> Unit,
    onLongPress: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val today = LocalDate.now()

    Column(
        Modifier
            .fillMaxWidth()
            .micaPanel(18.dp)
            .cardLongPress(onLongPress)
            .padding(horizontal = 17.dp, vertical = 15.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "接下来 · $days 天",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                color = scheme.onSurface,
            )
            Spacer(Modifier.weight(1f))
            if (items.size > com.gzucm.pocket.core.TodayLogic.MaxUpcoming) {
                Text(
                    "共 ${items.size} 条",
                    fontSize = 10.5.sp,
                    color = scheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
        }
        Spacer(Modifier.height(6.dp))

        if (items.isEmpty()) {
            Text(
                "$days 天内没有日程或考试",
                fontSize = 12.sp,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 6.dp),
            )
            return@Column
        }

        items.take(com.gzucm.pocket.core.TodayLogic.MaxUpcoming).forEach { o ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(G3CornerShape(10.dp))
                    .combinedClickable(
                        onClick = { onAgendaClick(o) },
                        onLongClick = onLongPress,
                    )
                    .padding(vertical = 7.dp, horizontal = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    dayLabel(o.date, today),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = scheme.primary,
                    modifier = Modifier.width(30.dp),
                )
                Text(
                    clock(o.startMinute),
                    fontSize = 11.sp,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.width(38.dp),
                )
                if (o.colorIndex == EXAM_COLOR_INDEX) {
                    Box(
                        Modifier
                            .padding(end = 5.dp)
                            .size(5.dp)
                            .clip(G3CornerShape(3.dp))
                            .background(scheme.error),
                    )
                }
                Text(
                    o.title,
                    fontSize = 13.sp,
                    color = scheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (o.place.isNotBlank()) {
                    Text(
                        o.place,
                        fontSize = 10.5.sp,
                        color = scheme.onSurfaceVariant.copy(alpha = 0.85f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .widthIn(max = 96.dp),
                    )
                }
            }
        }
    }
}

// ── 小工具 ────────────────────────────────────────────────────────

/**
 * 只响应长按、不响应点击。
 *
 * 为什么不用 `combinedClickable(onClick = {})`：那会给整张卡加一个
 * 「点了有波纹、但什么也没发生」的点击语义 —— 用户会以为卡坏了。
 * 这几张卡的长按只是「打开今日页显示」的快捷入口，点它本来就不该有反应。
 */
@Composable
internal fun Modifier.cardLongPress(onLongPress: () -> Unit): Modifier {
    val cb = androidx.compose.runtime.rememberUpdatedState(onLongPress)
    return this.pointerInput(Unit) {
        detectTapGestures(onLongPress = { cb.value() })
    }
}

/** `14:30`。startMinute 是当天第几分钟 */
private fun clock(minute: Int): String {
    val h = (minute / 60).coerceIn(0, 23)
    val m = (minute % 60).coerceIn(0, 59)
    return "${h.toString().padStart(2, '0')}:${m.toString().padStart(2, '0')}"
}

/**
 * 日期标签：明天 / 后天 / 周六。
 *
 * 不用「11-25」这种绝对日期：这张卡的目的是「知道那事还有几天」，
 * 相对说法读起来更快；超过三天就落到星期几上，学校里的日程本来也按周记。
 */
private fun dayLabel(date: LocalDate, today: LocalDate): String =
    when (ChronoUnit.DAYS.between(today, date)) {
        0L -> "今天"
        1L -> "明天"
        2L -> "后天"
        else -> "周" + "一二三四五六日"[date.dayOfWeek.value - 1]
    }

/** 天气接口给的是 `2026-11-21` 这样的字符串，解析不了就原样显示 */
private fun dayLabel(date: String, today: LocalDate): String {
    val d = runCatching { LocalDate.parse(date) }.getOrNull() ?: return date
    return dayLabel(d, today)
}

/** 「3 分钟前 / 2 小时前」——比绝对时刻更直观地表达新鲜度 */
private fun ageLabel(fetchedAt: Long): String {
    val age = (System.currentTimeMillis() - fetchedAt).coerceAtLeast(0L)
    return when {
        age < 60_000L -> "刚刚更新"
        age < 60L * 60_000L -> "${age / 60_000L} 分钟前"
        else -> "${age / (60L * 60_000L)} 小时前"
    }
}

// ── 天气图形 ──────────────────────────────────────────────────────
//
// 用的是**和风官方图标**（github.com/qwd/Icons，MIT），转成 VectorDrawable
// 放在 res/drawable/ic_qweather_*.xml。
//
// 为什么不再自己画：第一版用 Canvas 画了晴/云/雨/雷/雪/雾七种形状，
// 能跑，但和风一共有几十种天气现象（小雨转中雨、雷阵雨伴有冰雹、浮尘……），
// 自己画永远追不上；而官方图标本身是**单色 currentColor**，
// 直接用 Icon(tint = …) 就能跟着主题走，和 App 其他图标同一种质感。
//
// 只挑「形状差别够大」的十种：晴 / 夜晴 / 多云 / 夜多云 / 阴 / 雨 / 雷 / 雪 / 雾 / 未知。
// 同一类里的细分（小雨、中雨、大雨）在文字里写清楚，图形不必逐个对应。

/**
 * 天气现象代码 → 图标资源。
 *
 * 分段的依据和 [Weather.kindOf] 一致，但这里多做了两件事：
 *   · 夜间代码（150/151~153）用月亮系图标 —— 晚上 11 点显示大太阳很怪
 *   · 900/901（热/冷）归到晴/多云，跟 kind 的口径保持一致
 */
private fun glyphRes(code: String): Int {
    val c = code.toIntOrNull() ?: return R.drawable.ic_qweather_unknown
    return when {
        c == 100 || c == 900 -> R.drawable.ic_qweather_sunny
        c == 150 -> R.drawable.ic_qweather_moon
        c in 101..103 || c == 901 -> R.drawable.ic_qweather_cloudy
        c in 151..153 -> R.drawable.ic_qweather_cloud_moon
        c == 104 -> R.drawable.ic_qweather_overcast
        // 雷阵雨要排在 3xx 之前，否则会被画成普通雨
        c in 302..304 -> R.drawable.ic_qweather_storm
        c in 300..399 -> R.drawable.ic_qweather_rain
        c in 400..499 -> R.drawable.ic_qweather_snow
        c in 500..515 -> R.drawable.ic_qweather_fog
        else -> R.drawable.ic_qweather_unknown
    }
}

/**
 * 画一个天气图标。
 *
 * 参数是**原始代码**而不是 Kind：[Kind] 分不出昼夜，而「现在」这张卡
 * 一天里有三分之一的时间在夜里。
 */
@Composable
fun WeatherGlyph(code: String, size: Dp, tint: Color) {
    Icon(
        painter = painterResource(glyphRes(code)),
        contentDescription = null,
        modifier = Modifier.size(size),
        tint = tint,
    )
}
