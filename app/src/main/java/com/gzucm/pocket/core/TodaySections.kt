package com.gzucm.pocket.core

import java.time.LocalDate

/**
 * 今日页上的五块内容。
 *
 * 原来只有四块（天气 / 电费 / 接下来 / 本周概览），课程卡**固定夹在中间**，
 * 规则是「排前两个的摆课程上面、其余摆下面」。做完排序才发现这条规则会闹鬼：
 * 用户减掉电费和本周概览之后，剩下的「接下来」会突然从课程下方跳到上方 ——
 * 因为他从第三位变成了第二位。
 *
 * 所以把课程卡也放进顺序里：**它可以拖，但不能减**（[removable]）。
 * 这样「顺序」是唯一的真相，用户拖到哪就在哪，不会有意外的跳位。
 *
 * [key] 是写进 SharedPreferences 的稳定标识，**改名会丢用户已存的顺序**。
 */
enum class TodayBlock(
    val key: String,
    val label: String,
    val removable: Boolean = true,
    /**
     * 是否「这一版才新增的块」。
     *
     * 老配置里必然没有它。`normalized()` 把缺失的块补进 [TodaySections.allOrder] 时，
     * 默认会把这些块记进 `hiddenSet`（语义是「老配置里没提到 = 用户关掉了」）——
     * 那条规则对早就存在的块是对的，对**新块**就成了 bug：
     * 用户升级上来会凭空少一块，而且他不会知道有这一块可以打开。
     *
     * 所以新块打这个标记，补齐时**不**记进 hiddenSet，直接出现在页面上。
     */
    val addedInThisVersion: Boolean = false,
) {
    WEATHER("weather", "天气"),
    POWER("power", "宿舍电量"),
    UPCOMING("upcoming", "接下来"),
    WEEK_OVERVIEW("week", "本周概览"),

    /** 今日课程。永远显示，但位置可以拖 —— 有人喜欢课程在最上面 */
    COURSE("course", "今日课程", removable = false),

    /**
     * 今天的截止任务 / 待办 + 今天的日程（2026-11-22 从课程块里拆出来）。
     *
     * 原来它挂在「今日课程」下面，跟着课程一起移动、也不能减 ——
     * 致辉问「排序里少了待办与日程，它现在算在哪」。答案就是「算在课程里」，
     * 但那不合理：它是独立的一块内容，有自己的标题，凭什么都听课程的。
     */
    TASKS("tasks", "待办与日程", addedInThisVersion = true),
    ;

    companion object {
        fun fromKey(k: String?): TodayBlock? = entries.firstOrNull { it.key == k }
    }
}

/**
 * 今日页显示配置。
 *
 * ## 为什么是「全量顺序 + 隐藏集合」
 *
 * 2026-11-20 晚，第一版是「[order] 里只放显示中的块」：减掉 = 从列表里删掉，
 * 加回来 = 接到末尾。致辉一句话点破：**「减掉项目再加回去没有回到原来的位置」**。
 *
 * 删掉的那一刻位置信息就没了，事后只能靠猜（按默认顺序插？按邻居插？），
 * 怎么猜都会在用户自己拖过顺序之后出错。所以改成：
 *
 *   · [allOrder] —— **五块的全量顺序，被减掉的也在里面**。拖动改的是它。
 *   · [hiddenSet] —— 谁不显示。减掉只往这里加一个 key，[allOrder] 一个字不动。
 *
 * 于是「减掉 → 加回来」只是一次 `hiddenSet` 的增删，位置天然还在原地。
 * 连续减掉好几块再逐个加回来也不会乱序 —— 因为顺序从来没被删过。
 *
 * [order] 是两者的组合视图（显示中的、按顺序），UI 和渲染读的都是它，
 * 和第一版的语义完全一样。
 */
data class TodaySections(
    /** 五块的全量顺序，含被减掉的。空列表不合法，[normalized] 会补齐 */
    val allOrder: List<TodayBlock> = DefaultOrder,
    /** 被减掉的块。减掉不动 [allOrder]，所以加回来能回到原位 */
    val hiddenSet: Set<TodayBlock> = emptySet(),
    /** 「接下来」往后看几天（1~14） */
    val upcomingDays: Int = DefaultDays,
) {
    /**
     * 显示中的块，按页面顺序。
     *
     * 每次读都重新过滤一遍（最多五项）。故意不做缓存：data class 的
     * 缓存字段会让 `copy` 和相等性判断变得难写，而这五个元素的开销可以忽略。
     */
    val order: List<TodayBlock> get() = allOrder.filter { it !in hiddenSet }

    // ── 只读视图：老代码继续用 sections.power 这种写法 ──────────────
    val weather: Boolean get() = TodayBlock.WEATHER in order
    val power: Boolean get() = TodayBlock.POWER in order
    val upcoming: Boolean get() = TodayBlock.UPCOMING in order
    val weekOverview: Boolean get() = TodayBlock.WEEK_OVERVIEW in order

    /**
     * 被减掉的块，按枚举声明顺序。
     *
     * 这一组用户不控制顺序（它永远排在列表最下面、字变灰），
     * 用枚举顺序而不是「减掉的先后」—— 后者会让同一组东西每次打开都换个排法。
     */
    val hidden: List<TodayBlock>
        get() = TodayBlock.entries.filter { it in hiddenSet && it.removable }

    fun isOn(block: TodayBlock): Boolean = block in order

    /**
     * 减掉一块：只记进 [hiddenSet]，[allOrder] 不动 —— 位置就是这么保住的。
     * 课程不可减，直接原样返回。
     */
    fun hide(block: TodayBlock): TodaySections =
        if (block.removable && block in order) copy(hiddenSet = hiddenSet + block) else this

    /** 加回来：从 [hiddenSet] 里去掉，它自然回到 [allOrder] 里原来的位置 */
    fun show(block: TodayBlock): TodaySections {
        if (block in order) return this
        // 极端情况：allOrder 里根本没有它（存储被手改坏）—— 先补齐再显示
        val base = if (block in allOrder) this else normalized()
        return base.copy(hiddenSet = base.hiddenSet - block)
    }

    /** 点一下 −/+ 的统一入口 */
    fun toggle(block: TodayBlock): TodaySections =
        if (!block.removable) this
        else if (block in order) hide(block) else show(block)

    /**
     * 用一份新的「显示顺序」替换显示中的那部分，被减掉的块保持它们在 [allOrder] 里的位置。
     *
     * 拖动排序落地时调这个。为什么不是直接 `copy(allOrder = 新列表)`：
     * 拖动的列表里看不见隐藏的块，直接覆盖会把它们丢掉 —— 那「减掉再加回原位」
     * 就又不成立了。
     *
     * 做法是把 [allOrder] 里属于「显示中」的那些槽位，按顺序填上新值。
     * 例：allOrder = [A,B,C,D]，B 被减掉 → 槽位 0/2/3；
     * 新顺序 [C,A,D] 依次填进 0/2/3 → [C,B,A,D]，B 仍在第 1 位。
     */
    fun withOrder(newVisible: List<TodayBlock>): TodaySections {
        val slots = allOrder.indices.filter { allOrder[it] !in hiddenSet }
        if (newVisible.size != slots.size) {
            /*
             * 数量对不上说明调用方给的列表和当前显示的不一致（正常路径不会走到）。
             *
             * 2026-11-20 第一版在这里写成「以 newVisible 为准重建」—— 结果实机
             * 丢过一次块：某块既不在显示组也不在隐藏组，界面上凭空消失。
             * 所以这里的铁律是 **宁可顺序退让，也一个块都不能丢**：
             * 把 allOrder 里没出现在 newVisible 中的全部接到末尾。
             */
            val rest = allOrder.filter { it !in newVisible }
            return copy(allOrder = (newVisible.distinct() + rest).distinct())
        }
        val list = allOrder.toMutableList()
        slots.forEachIndexed { i, slot -> list[slot] = newVisible[i] }
        return copy(allOrder = list)
    }

    /**
     * 拖动排序。[from] / [to] 都指 [order]（也就是界面上看得见的列表）的下标。
     *
     * 越界直接忽略而不是抛异常：拖拽手势里的下标来自实时布局，
     * 一次快速甩动可能算出界，这里悄悄吞掉比闪退好。
     */
    fun move(from: Int, to: Int): TodaySections {
        val visible = order
        if (from == to) return this
        if (from !in visible.indices || to !in visible.indices) return this
        val list = visible.toMutableList()
        list.add(to, list.removeAt(from))
        return withOrder(list)
    }

    /**
     * 补齐 [allOrder] 里缺的块。
     *
     * 只在「读到老配置」时真的缺东西：1.4.x 存的是「显示中的块」，没提到的
     * 就是用户关掉的。这里按**默认顺序里的相对位置**把它们插回去，
     * 并记进 [hiddenSet] —— 于是老用户升级上来看到的还是原来那一页，
     * 而且随时点 + 就能回到它当年待的地方。
     *
     * 课程卡（[TodayBlock.removable] = false）不会被记进 hiddenSet：
     * 它只是老版本没这个字段，不是用户关掉的。
     */
    fun normalized(): TodaySections {
        val missing = TodayBlock.entries.filter { it !in allOrder }
        if (missing.isEmpty()) return this
        val list = allOrder.toMutableList()
        val extraHidden = hiddenSet.toMutableSet()
        missing.forEach { block ->
            val rank = DefaultOrder.indexOf(block)
            val anchor = if (rank < 0) -1 else list.indexOfFirst {
                DefaultOrder.indexOf(it).let { i -> i >= 0 && i > rank }
            }
            list.add(if (anchor < 0) list.size else anchor, block)
            // 新块不是「用户关掉的」，别让他升级上来就少一块
            if (block.removable && !block.addedInThisVersion) extraHidden += block
        }
        return copy(allOrder = list, hiddenSet = extraHidden)
    }

    companion object {
        const val MinDays = 1
        const val MaxDays = 14
        const val DefaultDays = 7

        /**
         * 默认顺序 = 2026-11 之前写死的那个顺序（天气、电费、课程、接下来、本周概览）。
         *
         * 老用户升级上来看到的是原样的页面，不会因为「新增排序功能」而变样。
         */
        val DefaultOrder: List<TodayBlock> = listOf(
            TodayBlock.WEATHER,
            TodayBlock.POWER,
            TodayBlock.COURSE,
            TodayBlock.TASKS,
            TodayBlock.UPCOMING,
            TodayBlock.WEEK_OVERVIEW,
        )
    }
}

object TodayLogic {

    /** 「接下来」最多摆几条。再多就不是「提醒」而是第二张日程表了 */
    const val MaxUpcoming = 5

    /**
     * 未来 N 天的日程 / 考试。
     *
     * 三条口径：
     * 1. **不含今天** —— 今天的内容已经在上面那一段里了，重复一遍会让人以为撞车；
     * 2. 含第 N 天当天（`<= today + N`），「七天内」就是连头带尾都在；
     * 3. 按日期、时间、标题排序，保证同一天里早的在前。
     *
     * 入参是 `agendaOccurrences + examOccurrences` 的合并结果，
     * 两种来源在这里不做区分 —— 对用户来说都是「那天有事」。
     */
    fun upcoming(
        all: List<AgendaOccurrence>,
        today: LocalDate,
        days: Int,
    ): List<AgendaOccurrence> {
        val span = days.coerceIn(TodaySections.MinDays, TodaySections.MaxDays).toLong()
        val until = today.plusDays(span)
        return all
            .filter { it.date.isAfter(today) && !it.date.isAfter(until) }
            .sortedWith(compareBy({ it.date }, { it.startMinute }, { it.title }))
    }
}
