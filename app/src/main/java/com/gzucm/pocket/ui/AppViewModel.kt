package com.gzucm.pocket.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gzucm.pocket.Graph
import com.gzucm.pocket.core.Agenda
import com.gzucm.pocket.core.AgendaLogic
import com.gzucm.pocket.core.AgendaOccurrence
import com.gzucm.pocket.core.AgendaStore
import com.gzucm.pocket.core.CLASS_SYNC_INTERVAL_MS
import com.gzucm.pocket.core.Campus
import com.gzucm.pocket.core.ClassPlan
import com.gzucm.pocket.core.ClassPlanIndex
import com.gzucm.pocket.core.ClassTaskState
import com.gzucm.pocket.core.ClassPrompt
import com.gzucm.pocket.core.ClassReminder
import com.gzucm.pocket.core.ClassSubscription
import com.gzucm.pocket.core.ClassSyncApi
import com.gzucm.pocket.core.ClassSyncStore
import com.gzucm.pocket.core.CourseGrade
import com.gzucm.pocket.core.CourseSession
import com.gzucm.pocket.core.EXAM_COLOR_INDEX
import com.gzucm.pocket.core.EXAM_PREFIX
import com.gzucm.pocket.core.ExamItem
import com.gzucm.pocket.core.FitnessGrade
import com.gzucm.pocket.core.LevelGrade
import com.gzucm.pocket.core.Net
import com.gzucm.pocket.core.NetError
import com.gzucm.pocket.core.PowerDaily
import com.gzucm.pocket.core.PowerInfo
import com.gzucm.pocket.core.Semester
import com.gzucm.pocket.core.TodaySections
import com.gzucm.pocket.core.Weather
import com.gzucm.pocket.core.autoImportIds
import com.gzucm.pocket.core.canManualSync
import com.gzucm.pocket.core.classTaskStates
import com.gzucm.pocket.core.withDeclined
import com.gzucm.pocket.core.withoutSub
import com.gzucm.pocket.core.detachFromClass
import com.gzucm.pocket.core.mergeClassSync
import com.gzucm.pocket.core.newVersions
import com.gzucm.pocket.core.promptIgnored
import com.gzucm.pocket.core.promptShown
import com.gzucm.pocket.core.shouldPrompt
import com.gzucm.pocket.core.shouldSync
import com.gzucm.pocket.core.subOf
import com.gzucm.pocket.core.syncedAt
import com.gzucm.pocket.core.withSub
import com.gzucm.pocket.data.Ecard
import com.gzucm.pocket.data.Jw
import com.gzucm.pocket.data.Yjs
import com.gzucm.pocket.ui.schedule.ClassPlanBarState
import com.gzucm.pocket.ui.schedule.ClaimablePlan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

enum class Phase { Loading, NeedLogin, Ready }

/**
 * 首页「待办与日程」卡顶部那一行，外加一个待弹的居中窗。
 *
 * [bar] 一定是四态之一（没有清单时是 Empty），[prompt] 只有「该打扰用户」时才非空。
 * 两者分开是因为它们生命周期不同：bar 是常驻的，prompt 关掉就该消失。
 */
data class ClassPlanHint(
    val bar: ClassPlanBarState,
    val prompt: ClassPlanPrompt? = null,
    /** 被学委删掉、本地还留着的条数。>0 时那行下面叠一条橙色提示 */
    val orphaned: Int = 0,
)

/**
 * 清单详情面板要显示的全部内容（2026-11-22）。
 *
 * [states] 在这里算好再交给 UI，不让 UI 自己对照日程 ——
 * `classTaskStates` 是 core 里的纯函数，能单测；UI 只管照着画。
 * 导入之后状态会变（新增变已加入），所以每次确认都要重建一份。
 */
data class ClassPlanDetailUi(
    val id: String,
    /** 学委名字，也是这份清单的密钥 */
    val key: String,
    val plan: ClassPlan,
    /** 键 = `ClassTaskDto.id` */
    val states: Map<String, ClassTaskState>,
    /** 是否已认领（有订阅）。没认领时底部按钮写「认领并导入」 */
    val claimed: Boolean,
    val syncedAt: Long,
    /** 明确拒绝过的条目，详情页默认不勾 */
    val declined: Set<String>,
    /** 正文来自本地缓存、网络没拉通：顶部要写「可能不是最新」 */
    val fromCache: Boolean = false,
)

/** 居中弹窗的三种场景（对应 ui/schedule 里的三个 Dialog） */
sealed interface ClassPlanPrompt {    /** 首次发现本班有清单：展示班级、让同学输入密钥 */
    data class Claim(
        /** 学籍里的行政班，只读展示 */
        val className: String,
        /** 只用来显示「有几份、谁维护」，界面上不出现密钥本身 */
        val plans: List<ClaimablePlan>,
    ) : ClassPlanPrompt

    /** 已认领的清单有新版本 */
    data class Update(val id: String, val key: String, val added: Int, val updated: Int) : ClassPlanPrompt

    /** 学委把整份清单删了 */
    data class Deleted(val key: String) : ClassPlanPrompt
}

data class UiState(
    val phase: Phase = Phase.Loading,
    val semester: Semester? = null,
    /** 学籍卡片（班级/年级/专业）。null = 还没拉到过，或这台设备还没登录过 */
    val studentCard: com.gzucm.pocket.core.StudentCard? = null,
    /**
     * 本机维护的**所有**班级清单（学委侧）。
     *
     * 2026-11-23 从「只有一份」改成列表：一个班通常不止一摊事 ——
     * 期末作业清单、见习安排清单、实验报告清单，各有各的名字和密钥。
     *
     * 和 `agenda`（自己的日程）分开两块 —— 一个是「我发给别人的」，
     * 一个是「我自己的事」，责任人和生命周期都不一样（见 ClassPlanStore 的注释）。
     */
    val classPlans: List<com.gzucm.pocket.core.ClassPlan> = emptyList(),
    /** 当前正在编辑哪一份，按密钥认 */
    val editingPlanKey: String = "",
    /** 已经发布到本班的那几份（按密钥），清单列表里标「已发布」/「草稿」用 */
    val publishedPlanKeys: Set<String> = emptySet(),
    val sessions: List<CourseSession> = emptyList(),
    val power: PowerInfo? = null,
    val powerDaily: List<PowerDaily> = emptyList(),
    val grades: List<CourseGrade> = emptyList(),
    val levels: List<LevelGrade> = emptyList(),
    val fitness: List<FitnessGrade> = emptyList(),
    /** 考试安排（教务自动抓取，只读）。字段保持原始字符串，不做日期解析 */
    val exams: List<ExamItem> = emptyList(),
    val syncing: Boolean = false,
    /**
     * 最近一次手动同步的结果，用来弹一次轻提示。
     *
     * 用「递增序号 + 文案」而不是纯字符串：同样的文案连续两次失败时，
     * 字符串没变化 → StateFlow 不发射 → 第二次 Toast 弹不出来。
     * seq 每次都变，UI 侧按 seq 判断「这是新的一条」。
     */
    val syncResultSeq: Int = 0,
    val syncResultText: String? = null,
    val gradesLoading: Boolean = false,
    /** 成绩分批抓取进度：已完成 / 总数（课程 → 等级 → 体测） */
    val gradesDone: Int = 0,
    val gradesTotal: Int = 0,
    /** 当前正在抓哪一批 */
    val gradesStage: String? = null,
    val powerLoading: Boolean = false,
    /** 电费读取失败的具体原因，为空表示没出错 */
    val powerError: String? = null,
    /**
     * 有没有一卡通会话。
     *
     * 必须放在 state 里而不是只读 `Net.cookies`：Cookie 是普通对象，
     * Compose 不会观察它，用户登录完一卡通回来看电费页，
     * 界面不会重绘，按钮还停在「登录一卡通」上。
     *
     * 跟着 state 走就一定能重组 —— 每次进电费页时由
     * [AppViewModel] 刷新一次。
     */
    val ecardLoggedIn: Boolean = false,
    val message: String? = null,
    val lastSync: Long = 0L,
    /** 逐周抓取进度：已完成 / 总周数。用于首屏显示「第 N/20 周」 */
    val progressDone: Int = 0,
    val progressTotal: Int = 0,
    /** 已经拿到数据的周次；不在里面的周显示「正在加载」，而不是空白 */
    val loadedWeeks: Set<Int> = emptySet(),
    /**
     * 用户自建日程（规则，未展开）。
     *
     * 刻意和 [sessions] 分开：sessions 是教务整表缓存，会被同步覆盖、
     * 被版本失效清空、被切学段/退出登录清掉；日程不能跟着一起消失。
     * 提醒排程读的是 sessions，所以日程天然不会进上课提醒（v1 约定）。
     */
    val agenda: List<Agenda> = emptyList(),
    /**
     * 天气。没配 key、没网、或缓存超过 6 小时都是 null ——
     * 卡片据此决定「显示 / 显示旧数据 / 整块不出现」。
     */
    val weather: Weather.Info? = null,
    val weatherRefreshing: Boolean = false,
    /** 拉取失败的原因（只在已经有一次成功数据时不显示，避免吵闹） */
    val weatherError: String? = null,
) {
    /**
     * 当前正在编辑的那份清单。
     *
     * 保留这个名字是为了不动几十处调用点 —— 它们要的都是「手上这份」，
     * 而不是「全部清单」。没有编辑目标时给一份空清单（key 为空）。
     */
    val classPlan: com.gzucm.pocket.core.ClassPlan
        get() = classPlans.firstOrNull { it.key == editingPlanKey }
            ?: com.gzucm.pocket.core.ClassPlan(key = "")
}

class AppViewModel(app: Application) : AndroidViewModel(app) {

    var state by mutableStateOf(UiState())
        private set

    /**
     * 电费「待刷新」标记。
     *
     * 以前这里挂的是隐藏 WebView 静默同步，但那条路一直不可靠：
     * 页面被判为不可见后 JS 定时器被节流，分页跑不完，明细常常拿不到。
     * 现在只打标记 —— 用户进电费页时弹窗同步一次（能看见进度，也真的能成功）。
     */
    var powerDirty by mutableStateOf(false)
        private set

    /**
     * 电费同步失败后调用：把「待刷新」标记清掉，让 10 分钟冷却生效。
     *
     * 原来失败分支只记了尝试时间，powerDirty 还是 true；进电费页的判定是
     * `powerDirty || needsAutoSync(...)`，冷却被短路 —— 结果每次切到电费页都重新弹窗。
     * 用户手动点「同步」会重新打标记，不影响主动刷新。
     */
    fun onPowerSyncFailed() {
        powerDirty = false
    }

    // ── 天气 ────────────────────────────────────────────────────────────
    //
    // 和风天气，全局最多 1 次/小时（见 Weather.MinIntervalMs）。
    // 触发点只有两处：冷启动、从后台回到前台 —— 都走 [maybeRefreshWeather]，
    // 由它自己判断「距上次不足一小时就跳过」，所以调用方不用做任何判断。
    //
    // **今日页把天气关掉之后，这里一次都不请求**（2026-11-20 致辉提的「省额度」）。
    // 判据放在 ViewModel 而不是 UI：卡片不渲染不等于不联网，
    // 之前就是 UI 隐藏着、后台照拉，每小时白烧一次额度。

    /** 冷启动时先摆缓存，再决定要不要联网 */
    fun loadWeather() {
        if (!Weather.enabled) return
        if (!store.todaySections.weather) return
        Weather.cached(store)?.let { cached ->
            if (state.weather == null) state = state.copy(weather = cached)
        }
        maybeRefreshWeather()
    }

    fun maybeRefreshWeather() {
        if (!Weather.enabled) return
        // 关掉就是关掉：不拉、也不排后续刷新
        if (!store.todaySections.weather) return
        if (weatherJob?.isActive == true) return
        val last = state.weather?.fetchedAt ?: 0L
        if (!Weather.canRefresh(last, System.currentTimeMillis())) return
        weatherJob = viewModelScope.launch {
            state = state.copy(weatherRefreshing = true)
            val info = Weather.fetch()
            state = if (info == null) {
                /*
                 * 失败不清旧数据：手里有一份一小时前的天气，
                 * 比换成一个「获取失败」的空卡有用得多。
                 */
                state.copy(
                    weatherRefreshing = false,
                    // 具体原因由 Weather 记（key 错 / 超额 / 没网），这里只做兜底
                    weatherError = Weather.lastError ?: "天气暂时取不到",
                )
            } else {
                Weather.save(store, info)
                state.copy(weather = info, weatherRefreshing = false, weatherError = null)
            }
        }
    }

    /**
     * 今日页显示配置变了。
     *
     * 唯一需要「立刻产生动作」的是天气：从关到开时补一次拉取，
     * 否则用户打开开关后要等到下一次冷启动才见到卡片，会以为开关坏了。
     * 反向（开→关）不用管 —— 卡片已经不渲染，请求也就不会再发。
     *
     * 这里仍然受 [Weather.canRefresh] 的一小时节流：刚拉过就开开关，不会重复打接口。
     */
    fun onTodaySectionsChanged(old: TodaySections, new: TodaySections) {
        if (!old.weather && new.weather) maybeRefreshWeather()
    }

    /**
     * 当前学段。登录页要用它决定默认选哪个 tab，
     * 页面标题也靠它区分「本科生 / 研究生」。
     */
    val campus: Campus
        get() = store.campus

    /**
     * 登录页该默认选中哪个学段。
     *
     * **没主动选过就是本科**（致辉 2026-11-02 定）：
     * 这个 App 大部分使用者是本科生，默认停在研究生等于坑大多数人。
     *
     * 注意不能直接用 [campus]：那个是「当前数据是谁的」，
     * 可能因为帮别人看过一次研究生账号就停在 GRADUATE，
     * 之后本科生每次打开登录页都要手动切回来。
     */
    val rememberedCampus: Campus
        get() = com.gzucm.pocket.ui.login.LoginFlow.loginDefaultCampus(store.pickedCampus)

    /** 用户在登录页主动点了学段按钮 —— 记住他的选择 */
    fun rememberCampusPicked(c: Campus) {
        store.pickedCampus = c
    }

    // ── 自定义日程 ────────────────────────────────────────────────
    //
    // 只动 state.agenda 和独立存储，**不碰 state.sessions**。
    // 这样同步、缓存版本失效、切学段、退出登录、提醒重排全都看不到日程，
    // 两边互不干扰。展开成每周实例是 UI 层的事（AgendaLogic.expand）。

    /** 新增或编辑一条日程（按 id 覆盖） */
    fun saveAgenda(item: Agenda) {
        val next = agendaStore.upsert(item)
        state = state.copy(agenda = next)
        rescheduleAgenda(next)
    }

    /**
     * 整表替换日程（班级作业导入用）。
     *
     * 不走 [saveAgenda] 逐条 upsert：一次导入可能几十条，
     * 每条都写一次 prefs、重排一遍闹钟，几十次同步磁盘 + 几十轮 AlarmManager
     * 能把主线程卡住两秒。这里只写一次、只重排一次。
     */
    fun replaceAgendas(list: List<Agenda>) {
        agendaStore.save(list)
        state = state.copy(agenda = list)
        rescheduleAgenda(list)
    }

    /**
     * 存班级清单（学委维护的那份）。
     *
     * 不走 [replaceAgendas]：那份是「我的日程」，这份是「我发给别人的清单」。
     * 两套存储分开，删掉自己日程里的一条不会让全班的清单少一条。
     */
    fun saveClassPlan(plan: com.gzucm.pocket.core.ClassPlan) {
        val list = state.classPlans.toMutableList()
        /*
         * 定位顺序很重要：先按「当前编辑的那份」找，再按密钥找。
         *
         * 学委改密钥时会走到这里 —— 那时 plan.key 已经是新值，按它找只能新增一份，
         * 原来那份（旧密钥）就变成永远够不着的孤儿。按 editingPlanKey 找才是「改」。
         */
        val byEditing = list.indexOfFirst { it.key == state.editingPlanKey }
        val byKey = list.indexOfFirst { it.key == plan.key }
        when {
            byEditing >= 0 -> list[byEditing] = plan
            byKey >= 0 -> list[byKey] = plan
            else -> list.add(plan)
        }
        classPlanStore.saveAll(list)
        state = state.copy(classPlans = list, editingPlanKey = plan.key)
    }

    /**
     * 新建一份空清单，直接进入编辑。
     *
     * 先给个临时密钥（`tmp-` 前缀）：还没填名字和密钥时它也得有个身份，
     * 否则 `editingPlanKey` 指不到东西。学委填完真密钥保存时，
     * [saveClassPlan] 会按 editingPlanKey 把这一份替换掉。
     */
    fun newClassPlan(name: String) {
        val card = state.studentCard
        val tmpKey = "tmp-" + System.currentTimeMillis().toString(36)
        val fresh = com.gzucm.pocket.core.ClassPlan(
            key = tmpKey,
            name = name.trim(),
            maintainer = card?.name.orEmpty(),
            className = card?.className.orEmpty(),
        )
        val list = state.classPlans + fresh
        classPlanStore.saveAll(list)
        state = state.copy(classPlans = list, editingPlanKey = tmpKey)
    }

    /** 按云端订阅表刷新「哪些清单已发布」，清单列表的「已发布 / 草稿」靠它 */
    private fun refreshPublishedPlanKeys() {
        val keys = classSyncStore.loadState().subs.map { it.key }.toSet()
        state = state.copy(publishedPlanKeys = keys)
    }

    /**
     * 重新从存储读一遍本机清单（2026-11-23）。
     *
     * 班级日程已经是独立 Activity，它有自己的 [AppViewModel] 实例 —— 在那边
     * 保存 / 新建 / 删除清单只写进 SharedPreferences，主界面这个 VM 的内存 state
     * 一个字都不会变。不重读的话，从 Activity 返回首页还是旧清单（少一份、
     * 名字是旧的、「已发布」标记也停在发布前）。
     *
     * 顺带重建首页那一行：同学侧的认领 / 取消认领改的是订阅表，
     * 同样只有重新算一遍才知道「现在还算不算已认领」。
     */
    fun reloadClassPlans() {
        val list = classPlanStore.loadAll()
        state = state.copy(
            classPlans = list,
            // 正在编辑的那份可能已经被删掉了，退回第一份，免得指着一个不存在的密钥
            editingPlanKey = state.editingPlanKey
                .takeIf { key -> list.any { it.key == key } }
                ?: list.firstOrNull()?.key.orEmpty(),
        )
        refreshPublishedPlanKeys()
        rebuildClassPlanHint()
    }

    /**
     * 重新从存储读一遍日程（2026-11-23）。
     *
     * 班级日程 Activity 里「导入到我的日程」/「认领并导入」写的是 AgendaStore，
     * 主界面这个 VM 的 `state.agenda` 还是导入前那份 —— 不重读的话，
     * 回到今日页/课表看不到刚导入的作业，得等下次冷启动。
     *
     * 不重排提醒：写入方（replaceAgendas）已经把闹钟重排过了，
     * 这里只是把同一份数据读回内存。
     */
    fun reloadAgendas() {
        state = state.copy(agenda = agendaStore.load())
    }

    /** 切到另一份清单去编辑（null = 回到清单列表） */
    fun editClassPlan(key: String?) {
        state = state.copy(editingPlanKey = key.orEmpty())
    }

    /** 删掉本机的一份清单（只删本地，云端那份要走「删除已发布的清单」） */
    fun removeLocalClassPlan(key: String) {
        val list = state.classPlans.filterNot { it.key == key }
        classPlanStore.saveAll(list)
        state = state.copy(
            classPlans = list,
            editingPlanKey = if (state.editingPlanKey == key) "" else state.editingPlanKey,
        )
    }

    // ── 班级日程：云端同步（2026-11-22） ──────────────────────────
    //
    // 分工：这里只负责「串起来」—— 什么时候发请求、结果往哪写。
    // 判断全在 core/ClassSync.kt 的纯函数里（能单测），显示全在 ui 层。
    //
    // 为什么不放在后台 Worker：国产 ROM 会把 Worker 杀掉（OPPO 上实测过），
    // 所以「每天一次」靠前台检查 —— 打开 App 时看距上次多久，超 24h 才发。
    // Worker 只当兜底，不指望它。

    private val classSyncStore = ClassSyncStore(getApplication())
    private val classSyncApi = ClassSyncApi()

    /** 首页「待办与日程」卡顶部那一行 + 待弹的窗。null = 功能不可用 */
    var classPlanHint by mutableStateOf<ClassPlanHint?>(null)
        private set

    /** 同步 / 导入后的一句话提示，UI 显示几秒就清掉 */
    var classPlanToast by mutableStateOf<String?>(null)
        private set

    fun clearClassPlanToast() { classPlanToast = null }

    /**
     * 清单详情（点首页那一行打开）。null = 面板没开。
     *
     * 2026-11-22 加：原来点「认领」是**直接全量导入**，同学根本不知道
     * 自己要吃进什么。现在改成先看清单、勾选、再导入。
     */
    var classPlanDetail by mutableStateOf<ClassPlanDetailUi?>(null)
        private set

    /**
     * 从本地缓存算出那一行。**不联网**。
     *
     * 三个数据源：index（本班有哪些清单）、state（我认领了哪份、同步到哪一版）、
     * agenda（有没有被学委删掉的条目）。任何一步缺数据都退化成 Empty，不抛。
     */
    private fun rebuildClassPlanHint(prompt: ClassPlanPrompt? = null) {
        /*
         * 没配云函数、或者还没登录拿不到班级 → 整块不出现。
         *
         * 不要退化成 Empty 态：那会让每个没用这个功能的同学，
         * 都在首页看到一行「还没有班级日程」—— 一个他既没听过也没法用的功能。
         */
        if (!classSyncApi.enabled || state.studentCard == null) {
            classPlanHint = null
            return
        }
        val idx = classSyncStore.loadIndex()
        val st = classSyncStore.loadState()
        val orphaned = state.agenda.count { it.classRemoteGone }
        val bar: ClassPlanBarState = when {
            idx == null || idx.plans.isEmpty() -> ClassPlanBarState.Empty
            st.subs.isEmpty() -> ClassPlanBarState.Unclaimed(
                idx.plans.map {
                    ClaimablePlan(it.id, it.key, it.count, it.updatedAt, it.ownerName, it.name)
                },
            )
            else -> {
                // 认领了多份时只拿最新那份做代表 —— 首页一行放不下两份
                val sub = st.subs.maxByOrNull { it.importedUpdatedAt }!!
                val brief = idx.plans.firstOrNull { it.id == sub.id }
                when {
                    brief == null ->
                        ClassPlanBarState.Claimed(sub.id, sub.key, "", "", 0, st.lastSyncAt)
                    brief.updatedAt > sub.importedUpdatedAt -> ClassPlanBarState.Updated(
                        sub.id,
                        sub.key,
                        brief.ownerName,
                        brief.name,
                        sub.lastAdded,
                        sub.lastUpdated,
                        st.lastSyncAt,
                    )
                    else -> ClassPlanBarState.Claimed(
                        sub.id,
                        sub.key,
                        brief.ownerName,
                        brief.name,
                        brief.count,
                        st.lastSyncAt,
                    )
                }
            }
        }
        classPlanHint = ClassPlanHint(bar, prompt, orphaned)

        /*
         * 本班一份清单都没有时不显示这一行。
         *
         * 「还没有班级日程」对着一个从没听过这功能的人，只是一行噪音 ——
         * 他既不知道学委是谁、也没有可点的东西。等真有人发布了，
         * 同步到就会弹窗 + 出现这一行，那时候它才有意义。
         */
        if (bar is ClassPlanBarState.Empty) classPlanHint = null
    }

    /**
     * 该同步就同步。
     *
     * @param force 手动入口（点那行 / 设置里点同步）传 true —— 跳过 24h 限制，
     *              但仍受 60 秒防连点约束，免得手抖点五下打五轮请求。
     */
    fun syncClassPlan(force: Boolean = false) {
        val card = state.studentCard ?: return
        if (!classSyncApi.enabled) return
        val st = classSyncStore.loadState()
        val now = System.currentTimeMillis()
        if (force) {
            if (!canManualSync(st.lastSyncAt, now)) return
        } else if (!shouldSync(st.lastSyncAt, now)) {
            rebuildClassPlanHint()
            return
        }

        viewModelScope.launch {
            when (val r = classSyncApi.index(card.classKey)) {
                is ClassSyncApi.Result.Ok -> {
                    classSyncStore.saveIndex(r.value)
                    classSyncStore.updateState { it.syncedAt(System.currentTimeMillis()) }
                    decideClassPlanPrompt(r.value)
                }
                // 失败一律静默：班级日程不是主功能，断网时不该弹个红字骂人
                is ClassSyncApi.Result.Failed -> rebuildClassPlanHint()
            }
        }
    }

    /**
     * 拉到 index 之后决定「要不要打扰用户」。
     *
     * 优先级：有新版 > 还没认领 > 清单被删。同一时刻只弹一个窗。
     */
    private fun decideClassPlanPrompt(index: ClassPlanIndex) {
        val st = classSyncStore.loadState()
        val now = System.currentTimeMillis()

        // ① 已认领、远端有新版
        val fresh = newVersions(index, st).firstOrNull()
        if (fresh != null) {
            val sub = st.subOf(fresh.id)
            when (shouldPrompt(fresh, sub, st, now)) {
                ClassPrompt.DIALOG -> {
                    classSyncStore.updateState { it.promptShown(now) }
                    rebuildClassPlanHint(ClassPlanPrompt.Update(fresh.id, fresh.key, 0, 0))
                    return
                }
                ClassPrompt.BANNER -> {
                    rebuildClassPlanHint()
                    return
                }
                ClassPrompt.NONE -> Unit
            }
        }

        // ② 从没认领过，本班有清单 —— 一天最多问一次
        if (st.subs.isEmpty() && index.plans.isNotEmpty()) {
            val due = st.lastPromptAt == 0L || now - st.lastPromptAt > CLASS_SYNC_INTERVAL_MS
            if (due) {
                classSyncStore.updateState { it.promptShown(now) }
                rebuildClassPlanHint(
                    ClassPlanPrompt.Claim(
                        className = state.studentCard?.className.orEmpty(),
                        // key 只用来让 submitClassPlanKey 比对，界面不展示
                        plans = index.plans.map {
                            ClaimablePlan(it.id, it.key, it.count, it.updatedAt, it.ownerName, it.name)
                        },
                    ),
                )
                return
            }
        }

        // ③ 认领过的清单在云端没了（学委整份删了）
        //    notifiedUpdatedAt 抬到 MAX 当「已经提示过」的标记，避免每次同步都弹
        val missing = st.subs.firstOrNull { sub ->
            sub.notifiedUpdatedAt != Long.MAX_VALUE && index.plans.none { it.id == sub.id }
        }
        if (missing != null) {
            classSyncStore.updateState {
                it.withSub(missing.copy(notifiedUpdatedAt = Long.MAX_VALUE))
            }
            rebuildClassPlanHint(ClassPlanPrompt.Deleted(missing.key))
            return
        }

        rebuildClassPlanHint()
    }

    /**
     * 同学为**已选中的那一份**清单输入的密钥对不对。
     *
     * 对了就打开这份清单并返回 true（弹窗随之关掉）；不对返回 false，
     * 由弹窗显示「这个密钥对不上这一份清单」。
     *
     * 必须按 [planId] 定位：一个班可以有多份清单、各有各的密钥，
     * 以前「拿 key 反找清单」的做法会让同学输对别人的 key 就认领到别人那份，
     * 现在清单是弹窗里先选好的，这里只负责校验「这个 key 是不是这一份的」。
     *
     * 比对在本地 `index` 里做：清单的 key 是摘要里带的（服务端按班级列出），
     * 所以不需要多一次网络往返。**界面上从不展示这些 key** —— 同学必须手输，
     * 这就是「确认自己找对了」的那一步。
     */
    fun submitClassPlanKey(planId: String, input: String, onResult: (Boolean) -> Unit) {
        val key = input.trim()
        if (key.isEmpty()) {
            onResult(false)
            return
        }

        // 先拿本地已有的索引比：绝大多数情况一次就中
        val local = classSyncStore.loadIndex()?.plans?.firstOrNull { it.id == planId }
        if (local != null && local.key.trim() == key) {
            classPlanHint = null
            openClassPlanDetail(planId)
            onResult(true)
            return
        }

        /*
         * 本地没对上，**不能直接判「密钥错了」**。
         *
         * 两种情况都会走到这里：索引里还没有这份清单，或者有、但 key 还是旧的。
         * 2026-11-23 致辉在手机上输「钟伟熙」一直提示不对：他手机上缓存的是
         * 学委前一天那份索引（那时密钥还是旧值），而学委这边已经改过密钥重新发布了。
         * 同学的 App 一天只同步一次，本地索引可能一直是旧的 ——
         * 于是本地永远是新旧不一致 —— 输入正确答案也必然失败。
         *
         * 所以这里再往服务端要一次：拉到了就按 planId 重新找一遍再比，还不对才是真不对。
         */
        val card = state.studentCard
        if (card == null) {
            onResult(false)
            return
        }
        viewModelScope.launch {
            when (val r = classSyncApi.index(card.classKey)) {
                is ClassSyncApi.Result.Ok -> {
                    classSyncStore.saveIndex(r.value)
                    val plan = r.value.plans.firstOrNull { it.id == planId }
                    if (plan != null && plan.key.trim() == key) {
                        classPlanHint = null
                        openClassPlanDetail(planId)
                        onResult(true)
                    } else {
                        onResult(false)
                    }
                }
                is ClassSyncApi.Result.Failed -> onResult(false)
            }
        }
    }

    /**
     * 打开清单详情：拉正文 → 存缓存 → 交给面板。
     *
     * 2026-11-22 起「认领」不再是「直接全量导入」—— 同学要先看见有什么、
     * 勾掉不想要的，再确认。所以这个函数只负责把内容取到，不碰日程。
     *
     * 有缓存先显示：同学点开时不该对着空白转圈，尤其刚同步过、内容其实
     * 早就在本地了。网络回来之后再默默换成最新的（[ClassPlanDetailUi.fromCache] 变 false）。
     */
    fun openClassPlanDetail(id: String) {
        val card = state.studentCard ?: return
        val cached = classSyncStore.loadPlanCache(id)
        if (cached != null) classPlanDetail = buildClassPlanDetail(id, cached, fromCache = true)

        viewModelScope.launch {
            when (val r = classSyncApi.plan(card.classKey, id)) {
                is ClassSyncApi.Result.Ok -> {
                    classSyncStore.savePlanCache(id, r.value)
                    classPlanDetail = buildClassPlanDetail(id, r.value, fromCache = false)
                }
                // 有缓存就闭嘴用缓存（面板顶部已经写了「可能不是最新」）；没缓存才报错
                is ClassSyncApi.Result.Failed -> if (cached == null) {
                    classPlanToast = r.message.ifBlank { "连不上云端，稍后再试" }
                }
            }
        }
    }

    private fun buildClassPlanDetail(
        id: String,
        plan: ClassPlan,
        fromCache: Boolean,
    ): ClassPlanDetailUi {
        val st = classSyncStore.loadState()
        val sub = st.subOf(id)
        return ClassPlanDetailUi(
            id = id,
            key = plan.key,
            plan = plan,
            states = classTaskStates(plan, state.agenda),
            claimed = sub != null,
            syncedAt = st.lastSyncAt,
            declined = sub?.declinedIds.orEmpty().toSet(),
            fromCache = fromCache,
        )
    }

    /**
     * 确认导入勾选的那几条（详情页底部按钮）。
     *
     * 这里是**唯一**把班级清单灌进日程的入口 —— 自动同步只发提醒、不动数据，
     * 因为「要不要这条作业」得用户自己说了算（2026-11-22 定的）。
     */
    fun confirmClassPlanImport(selected: Set<String>) {
        val detail = classPlanDetail ?: return
        if (selected.isEmpty()) {
            classPlanToast = "一条都没勾，那就先不导入"
            return
        }
        val plan = detail.plan
        val (merged, result) = mergeClassSync(state.agenda, plan, onlyIds = selected)
        // 一次导入可能几十条，走 replaceAgendas 只写一次盘、只重排一次闹钟
        replaceAgendas(merged)

        /*
         * 没勾的全记成「明确不要」。
         *
         * 不记的话，用户下次同步会被告知「新增 3 条」—— 而那 3 条正是他
         * 刚亲手否掉的东西。详情页永远列全部条目，想反悔随时回去勾。
         */
        val declined = plan.tasks.map { it.id }.filterNot { it in selected }
        val now = System.currentTimeMillis()
        classSyncStore.updateState { st ->
            val oldSub = st.subOf(detail.id)
            st.withSub(
                ClassSubscription(
                    id = detail.id,
                    key = plan.key,
                    importedUpdatedAt = plan.updatedAt,
                    notifiedUpdatedAt = plan.updatedAt,
                    claimedAt = oldSub?.claimedAt?.takeIf { it > 0 } ?: now,
                    lastAdded = result.added,
                    lastUpdated = result.updated,
                    declinedIds = declined,
                ),
            )
        }
        classPlanDetail = buildClassPlanDetail(detail.id, plan, fromCache = detail.fromCache)
        classPlanToast = result.summary()
        rebuildClassPlanHint()
    }

    /**
     * 删掉自己发布到本班的清单（学委侧）。
     *
     * 只删云端那一份，**不动本地正在编辑的清单** —— 学委可能只是想从班上撤下来，
     * 不想把辛苦整理的条目也清空。所以这里只清订阅和缓存，`state.classPlan` 原样留着，
     * 想重新发布随时再按一次。
     *
     * 同学那边下次同步会看到「清单已被删除」，但他们已导入的日程不会消失
     * （客户端只是把 origin 清成本地日程）。
     */
    fun deletePublishedClassPlan(onResult: (String) -> Unit) {
        val card = state.studentCard ?: return
        val name = state.classPlan.key.trim()
        if (name.isEmpty()) {
            onResult("还没填密钥，不知道要删哪一份")
            return
        }
        val sub = classSyncStore.loadState().subs.firstOrNull { it.key == name }
        if (sub == null) {
            onResult("云端没有「$name」，可能已经删过了")
            return
        }
        viewModelScope.launch {
            when (val r = classSyncApi.delete(card.classKey, sub.id, card.studentId)) {
                is ClassSyncApi.Result.Ok -> {
                    classSyncStore.updateState { it.withoutSub(sub.id) }
                    classSyncStore.prunePlanCache(
                        classSyncStore.loadState().subs.map { it.id }.toSet(),
                    )
                    classPlanDetail = null
                    onResult("已删除「$name」。同学那边会看到提示，已导入的日程不会消失")
                    rebuildClassPlanHint()
                }
                is ClassSyncApi.Result.Failed -> onResult(r.message.ifBlank { "删除失败，稍后再试" })
            }
        }
    }

    /** 取消认领：不再同步这份清单，已导入的日程原样留成本地日程 */
    fun unsubscribeClassPlan(id: String) {
        classSyncStore.updateState { it.withoutSub(id) }
        classSyncStore.prunePlanCache(classSyncStore.loadState().subs.map { it.id }.toSet())
        classPlanDetail = null
        classPlanToast = "已取消认领，导入的日程还在你的日程里"
        rebuildClassPlanHint()
    }

    /*
     * 原来这里还有一个 requestClassPlanPrompt()：点首页那一行时算「该弹认领窗
     * 还是该开详情」。2026-11-23 认领改成独立 Activity 之后，这个判断搬到了
     * ClassPlanActivity 的 initialClaimPage（已认领直接进详情，否则先进认领页），
     * 这里就不再需要了 —— 两个 Activity 各有一个 VM，判断只能由拿到页面的那边做。
     */

    /** 关掉弹窗。记一次「忽略」——连续 3 次之后只留首页横幅，不再弹 */
    fun dismissClassPlanPrompt(countAsIgnore: Boolean = true) {
        if (countAsIgnore) classSyncStore.updateState { it.promptIgnored() }
        rebuildClassPlanHint()
    }

    /** 学委删了这条，本地保留：转成普通本地日程，此后不再跟云端比对 */
    fun keepDeletedAgenda(id: String) {
        val target = state.agenda.firstOrNull { it.id == id } ?: return
        saveAgenda(detachFromClass(target))
        rebuildClassPlanHint()
    }

    /** 学委删了这条，本地也不要了 */
    fun dropDeletedAgenda(id: String) {
        deleteAgenda(id)
        rebuildClassPlanHint()
    }

    /** 首页那行点「处理」：把第一条被删的条目交给调用方展开 */
    fun firstDeletedAgendaId(): String? = state.agenda.firstOrNull { it.classRemoteGone }?.id

    /**
     * 把清单发布到本班（学委侧）。回调给一句人话，UI 直接 Toast。
     *
     * [plan] 由面板传进来而不是从 `state.classPlan` 读：用户刚填的密钥、
     * 学籍里的班级这时候可能还没回写进 state，读旧值会发一份 key 为空的清单上去。
     */
    fun publishClassPlan(plan: com.gzucm.pocket.core.ClassPlan, onResult: (String) -> Unit) {
        val card = state.studentCard ?: return
        if (card.studentId.isBlank()) {
            onResult("学籍里没有学号，没法发布")
            return
        }
        if (plan.tasks.isEmpty()) {
            onResult("清单是空的，先加几条作业")
            return
        }
        if (plan.key.isBlank()) {
            onResult("先填密钥（学委名字）")
            return
        }
        viewModelScope.launch {
            when (val r = classSyncApi.publish(card.classKey, card.studentId, plan)) {
                is ClassSyncApi.Result.Ok -> {
                    // 清单列表里的「已发布 / 草稿」靠它
                    refreshPublishedPlanKeys()
                    // 发布者自己也记一条订阅：这样首页会显示「已发布 · N 条」，
                    // 而且同学改了别人的清单时能看到「有新版」（学委多设备时有用）
                    classSyncStore.updateState {
                        it.withSub(
                            ClassSubscription(
                                id = r.value.id,
                                key = plan.key,
                                importedUpdatedAt = r.value.updatedAt,
                                notifiedUpdatedAt = r.value.updatedAt,
                                claimedAt = System.currentTimeMillis(),
                            ),
                        )
                    }
                    onResult("已发布，本班同学下次打开 App 就会看到")
                    syncClassPlan(force = true)
                }
                is ClassSyncApi.Result.Failed -> onResult(r.message.ifBlank { "发布失败" })
            }
        }
    }

    /** 清掉一条的未读（打开详情、勾完成时调） */
    fun markClassPlanSeen(id: String) {
        val target = state.agenda.firstOrNull { it.id == id } ?: return
        if (!target.classUnread) return
        state = state.copy(
            agenda = agendaStore.upsert(target.copy(classUnread = false)),
        )
    }

    /** 删除一条日程（每周重复会整条删掉，v1 不支持单天例外） */
    fun deleteAgenda(id: String) {
        val next = agendaStore.delete(id)
        state = state.copy(agenda = next)
        rescheduleAgenda(next)
    }

    /**
     * 勾选 / 取消勾选一条截止任务。
     *
     * doneAt 存完成时刻（epoch millis），null = 未完成。不新增存储：任务也是 Agenda，
     * 和日程共用同一个 agendaStore —— 致辉 2026-11-17 定的「混在一个列表」。
     *
     * 不做撤销票据（2026-11-17 当天改掉）：完成后卡片不消失，而是变淡沉底，
     * 第二天自动不见 —— 想改回来直接再点一下那个勾就行，比 4.5 秒的撤销条好用。
     */
    fun setTaskDone(id: String, done: Boolean) {
        val target = state.agenda.firstOrNull { it.id == id && it.isTask } ?: return
        if ((target.doneAt != null) == done) return // 已经是目标状态，别重复写
        val updated = target.copy(doneAt = if (done) System.currentTimeMillis() else null)
        state = state.copy(agenda = agendaStore.upsert(updated))
    }

    /**
     * 日程变了就重排提醒（全量 cancel + 重新排）。
     *
     * 不做增量：改动可能是「改时间 / 改提前量 / 改日期 / 每周开关 / 删掉」，
     * 每种都要单独算「哪些旧闹钟该留」，分支比全量多得多；日程就几十条，全量是毫秒级。
     *
     * 没设提醒的日程会被 [AgendaReminder.plan] 直接过滤掉，同时把 12h 兜底任务停掉。
     */
    private fun rescheduleAgenda(rules: List<Agenda>) {
        val sem = state.semester
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                com.gzucm.pocket.core.AgendaReminder.scheduleAll(getApplication(), sem, rules)
            }.onFailure { android.util.Log.w("AgendaReminder", "重排失败", it) }
        }
    }

    /**
     * 按当前校历展开成可以画进课表的实例。
     * 没拿到校历（未登录 / 还没同步）时返回空 —— 日程界面照样能看列表，
     * 但课表上不画，免得硬造一套不依赖 Semester 的坐标系。
     */
    fun agendaOccurrences(semester: Semester?): List<AgendaOccurrence> =
        if (semester == null) emptyList() else AgendaLogic.expand(semester, state.agenda)

    /**
     * 首页要显示的截止任务 / 待办，按「未完成优先，已完成沉底」排序。
     *
     * 已完成的只留**今天勾掉的**（致辉 2026-11-17 定）：完成后不立刻消失，
     * 卡片变淡沉到日程区最下方，第二天自动不见。隔天的已完成任务不再返回 ——
     * 首页是「今天要做什么」，不是完成记录。
     *
     * 任务不进 [agendaOccurrences]（那是课表网格用的），单独一条路径传给首页。
     */
    fun todayTaskOccurrences(semester: Semester?): List<AgendaOccurrence> {
        val now = System.currentTimeMillis()
        val today = java.time.LocalDate.now()
        val todayStart = today.atStartOfDay(java.time.ZoneId.systemDefault())
            .toInstant().toEpochMilli()
        return state.agenda
            .filter { it.isTask }
            // 已完成：只保留「今天完成的」；未完成：全留
            .filter { it.doneAt == null || it.doneAt >= todayStart }
            .sortedWith(
                compareBy<Agenda> { com.gzucm.pocket.core.taskUrgency(it, now).ordinal }
                    .thenBy { it.deadlineAt ?: Long.MAX_VALUE }
                    .thenBy { it.title }
            )
            .map { com.gzucm.pocket.core.taskAsOccurrence(it, today, semester) }
    }

    /**
     * 把考试也转成课表条目，跟日程一起画进周课表和日视图。
     *
     * 约定 `sourceId` 以 [EXAM_PREFIX] 开头表示这是考试 —— 点击时用它分流：
     * 考试打开只读详情，日程打开编辑表单。用前缀而不是单独一个类型，
     * 是因为渲染层（placeSessions 之外的那套绘制、日视图混排、今日页）
     * 全都吃 `AgendaOccurrence`，加类型会把七处签名一起改掉。
     */
    fun examOccurrences(semester: Semester?): List<AgendaOccurrence> {
        if (semester == null) return emptyList()
        return state.exams.mapNotNull { e ->
            val date = e.dateOnly ?: return@mapNotNull null
            val s = e.startMinute ?: return@mapNotNull null
            val en = (e.endMinute ?: (s + 120)).coerceAtLeast(s + 30)
            AgendaOccurrence(
                key = "${EXAM_PREFIX}${examId(e)}",
                sourceId = "${EXAM_PREFIX}${examId(e)}",
                title = e.course,
                date = date,
                week = semester.weekOf(date),
                weekday = date.dayOfWeek.value,
                startMinute = s,
                endMinute = en,
                place = e.room,
                note = e.type,
                colorIndex = EXAM_COLOR_INDEX,
                unitStart = 0,
                unitEnd = 0,
                weekly = false,
                seat = e.seat,
            )
        }.sortedWith(
            /*
             * 按考试时间排（致辉 2026-11-20 指出）。
             *
             * 以前直接用 state.exams 的顺序 —— 那是教务返回的顺序，
             * 大致按课程走，和考试早晚无关；今日页又是「日程（已排序）+ 考试」拼接，
             * 结果同一天里上午的考试永远排在晚上的日程后面。
             */
            compareBy({ it.date }, { it.startMinute }, { it.title }),
        )
    }

    /** 考试的唯一键：同名课可能考好几次，所以带上日期和开考时间 */
    fun examId(e: ExamItem): String =
        "${e.course}|${e.date}|${e.time}|${e.seat}"

    /**
     * 刷新「有没有一卡通会话」并写进 state。
     *
     * 一卡通会话和课表会话是**完全独立**的两套：
     *   · 本科：登录教务时 CAS 会顺带跳一次一卡通，所以通常有
     *   · 研究生：只登了研究生系统，一卡通是空的
     *
     * 电费页用它决定空态怎么说 —— 「没登录」和「读了但失败」
     * 对用户来说是完全不同的下一步动作。
     *
     * 为什么要有这个方法：Cookie 存的是普通对象，Compose 观察不到它。
     * 用户在一卡通里登完回来，如果只读 Cookie 不写 state，
     * 界面不会重绘，按钮还停在「登录一卡通」上。
     */
    fun refreshEcardSession() {
        val has = Net.cookies.has("ecard.gzucm.edu.cn")
        if (state.ecardLoggedIn != has) {
            state = state.copy(ecardLoggedIn = has)
        }
    }

    fun markPowerDirty() {
        powerDirty = true
    }

    /** 上课提醒开关；关掉时把已排的闹钟一并撤销 */
    /** 上课提醒开关。必须是 Compose 状态：直接读 prefs 不会触发重组，Switch 就没有动画 */
    var classReminderOn by mutableStateOf(Graph.store.classReminderEnabled)
        private set

    /**
     * 登录态是不是已经失效了。
     *
     * 用来区分「真的没数据」和「看不了数据」——
     * 成绩页空着的时候，如果其实是没登录，提示「暂无成绩」就是误导。
     */
    var loginExpired by mutableStateOf(false)
        private set

    /**
     * 为什么被送到登录页（只在「点同步 → 发现会话过期 → 自动跳登录」时非空）。
     *
     * 冷启动未登录、手动退出登录都是 null —— 那些场景用户自己知道在干什么，
     * 不需要再解释。登录页据此显示一句说明和「稍后再登」的出口。
     */
    var reloginNotice by mutableStateOf<String?>(null)
        private set

    /**
     * 「重登完之后补一次同步」的标记。
     *
     * 为什么不复用 [reloginNotice]：`onJwReady` 在教务一通过的瞬间就把人放回主界面
     * （早于 `onLoggedIn`，因为还要等一卡通那 8 秒），那时说明条就该撤掉；
     * 但「把刚才没做完的那次同步补上」要留到 `onLoggedIn` 才执行。
     *
     * 没有这个标记的话，用户重登回来会看到**旧课表**（`onLoggedIn` 只在
     * `sessions` 为空时才同步），得再点一次同步 —— 等于白跳一趟。
     */
    private var syncAfterRelogin = false

    /** 有可用更新（首页设置按钮上的小红点） */
    var hasUpdate by mutableStateOf(
        Graph.store.latestVersion?.let {
            com.gzucm.pocket.core.Updater.isNewer(it, com.gzucm.pocket.BuildConfig.VERSION_NAME)
        } ?: false
    )
        private set

    /**
     * 每天静默查一次有没有新版本。
     *
     * 失败不提示、不记日期 —— 网络问题下次打开还能再试；
     * 只有真正拿到结果才写日期，避免失败一次就整天不检查。
     */
    /**
     * 正在检查更新的标记。
     *
     * 为什么需要它：bootstrap 和 ON_RESUME 几乎同时触发检查，
     * 第二次执行时第一次的结果还没写回 lastUpdateCheckDate，
     * 于是会重复请求一次 GitHub。加这个标记挡掉并发。
     *
     * 用普通的 Boolean 而不是 AtomicBoolean：所有读写都在主线程
     * （viewModelScope 默认 Dispatchers.Main.immediate），不存在竞态。
     */
    private var updateChecking = false

    private fun checkUpdateSilently() {
        val today = LocalDate.now().toString()
        if (store.lastUpdateCheckDate == today) return
        // 已经有一次在飞行中：直接返回，别重复打 GitHub
        if (updateChecking) return
        updateChecking = true
        viewModelScope.launch {
            try {
                val r = com.gzucm.pocket.core.Updater.check()
                when (r) {
                    is com.gzucm.pocket.core.Updater.Result.Newer -> {
                        /*
                         * 存**整条**信息，不只版本号。
                         *
                         * 关于页进去要直接显示「发现 v1.3.1（12.2 MB）」+ 下载按钮，
                         * 它需要包大小和下载地址。只存版本号的话，
                         * 用户从设置页看到「检测到新版本」进去却什么都没有，
                         * 还得手动点一次「检查更新」（致辉 2026-11-02 报的）。
                         */
                        com.gzucm.pocket.core.Updater.saveCachedInfo(store, r.info)
                        store.lastUpdateCheckDate = today
                        hasUpdate = true
                    }
                    com.gzucm.pocket.core.Updater.Result.UpToDate -> {
                        com.gzucm.pocket.core.Updater.saveCachedInfo(store, null)
                        store.lastUpdateCheckDate = today
                        hasUpdate = false
                    }
                    // 失败不写日期：网络问题下次打开还能再试，
                    // 不能因为一次失败就整天不检查
                    is com.gzucm.pocket.core.Updater.Result.Failed -> Unit
                }
            } finally {
                // 无论成功失败都要解锁，否则一次异常会让之后永远不再检查
                updateChecking = false
            }
        }
    }

    /**
     * 给 UI 用的「如果今天还没查过就查一次」。
     *
     * 从后台切回前台时调用。内部是幂等的（checkUpdateSilently 自带日期判断），
     * 所以频繁调用也不会重复打 GitHub。
     */
    fun checkUpdateIfStale() = checkUpdateSilently()

    /** 关于页手动查过之后同步一下红点状态 */
    fun refreshUpdateBadge() {
        hasUpdate = com.gzucm.pocket.core.Updater.loadCachedInfo(store) != null
    }

    /** 关于页进入时用的缓存信息（没有就返回 null） */
    fun cachedUpdate(): com.gzucm.pocket.core.Updater.UpdateInfo? =
        com.gzucm.pocket.core.Updater.loadCachedInfo(store)

    /** 上课提醒两档提前量，必须是 State 才会即时刷新 */
    var classLeadFree by mutableStateOf(Graph.store.classLeadFree)
        private set
    var classLeadBreak by mutableStateOf(Graph.store.classLeadBreak)
        private set

    fun setClassLead(free: Int?, breakMin: Int?) {
        if (free != null) {
            classLeadFree = free.coerceIn(5, 40)
            store.classLeadFree = classLeadFree
        }
        if (breakMin != null) {
            classLeadBreak = breakMin.coerceIn(5, 30)
            store.classLeadBreak = classLeadBreak
        }
        // 改了提前量要立刻重排，不然今天已排的还是旧时间
        reschedule()
    }

    /**
     * 增强提醒（setAlarmClock）。默认关，需要用户自己打开。
     *
     * 打开后状态栏会有闹钟图标，换来的是 ColorOS / HyperOS 省电策略基本推迟不了它。
     */
    var classReminderEnhanced by mutableStateOf(Graph.store.classReminderEnhanced)
        private set

    // 不叫 setClassReminderEnhanced：会和上面属性自动生成的 setter 撞 JVM 签名
    fun toggleReminderEnhanced(on: Boolean) {
        classReminderEnhanced = on
        store.classReminderEnhanced = on
        reschedule()
    }

    /** 当前能否排精确闹钟（Android 12+ 需用户授权）。设置页用它决定要不要提示去开 */
    fun canExactAlarm(): Boolean = ClassReminder.canExact(getApplication())

    /** 用户刚在系统设置里开了「闹钟和提醒」：把非精确的那批换成精确的 */
    fun onExactAlarmGranted() = reschedule()

    /**
     * 按当前状态重排提醒。所有入口统一走这里，保证提前量 / 增强开关一定带上。
     *
     * 排闹钟要反序列化不了什么，但会碰 SharedPreferences 和 AlarmManager 的 binder 调用，
     * 几十条也就几毫秒；放 IO 只是为了不在点开关的那一帧里做系统调用。
     */
    private fun reschedule(
        sem: Semester? = state.semester,
        ses: List<CourseSession> = state.sessions,
    ) {
        if (sem == null || ses.isEmpty()) return
        val enabled = store.classReminderEnabled
        val free = classLeadFree
        val brk = classLeadBreak
        val enhanced = classReminderEnhanced
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                ClassReminder.scheduleAll(getApplication(), sem, ses, enabled, free, brk, enhanced)
            }.onFailure { android.util.Log.w("ClassReminder", "重排失败", it) }
        }
    }

    /** 电费不足提醒开关（同样是 Compose 状态，否则 Switch 不动） */
    var powerAlertOn by mutableStateOf(Graph.store.powerAlertEnabled)
        private set

    fun setPowerAlert(enabled: Boolean) {
        powerAlertOn = enabled
        store.powerAlertEnabled = enabled
        if (enabled) {
            runCatching { com.gzucm.pocket.core.PowerAlert.ensureChannel(getApplication()) }
            com.gzucm.pocket.core.PowerAlert.schedule(getApplication())
        } else {
            com.gzucm.pocket.core.PowerAlert.cancel(getApplication())
        }
    }

    fun setClassReminder(enabled: Boolean) {
        classReminderOn = enabled
        store.classReminderEnabled = enabled
        if (!enabled) {
            // 关掉时即使课表没加载也要取消干净（包括每日续命任务）
            viewModelScope.launch(Dispatchers.IO) {
                runCatching {
                    ClassReminder.cancelScheduled(getApplication())
                    com.gzucm.pocket.core.ReminderRefreshWorker.cancel(getApplication())
                }
            }
            return
        }
        reschedule()
    }

    private val store = Graph.store

    /** 日程独立存储，声明在 init 之前：bootstrap 就要读它 */
    private val agendaStore = AgendaStore(getApplication())
    /** 班级清单的独立存储：和 agendaStore 分开（一个发给别人、一个是自己的） */
    private val classPlanStore = com.gzucm.pocket.core.ClassPlanStore(getApplication())

    /**
     * 正在跑的那份同步。**必须声明在 init 之前**：init 里 bootstrap() 就会调
     * syncQuietly()，Kotlin 按声明顺序初始化属性，放在后面的话那一刻它还没被赋初值，
     * 之后执行到声明处又会被重置成 null —— 刚起的 Job 引用就丢了，防重入形同虚设。
     */
    private var syncJob: kotlinx.coroutines.Job? = null

    /**
     * 天气请求的防重入闸。
     *
     * 和 [syncJob] 同理：**必须声明在 init 之前** —— bootstrap() 里就会调
     * loadWeather()，Job 在建完之后如果被这里的初始化语句重置成 null，
     * 下一次 ON_RESUME 就会再发一份请求，一小时一次的节流直接失效。
     */
    private var weatherJob: kotlinx.coroutines.Job? = null

    /** 当前周（1 起）。用户可手动校正，默认为按校历推算。 */
    var currentWeek by mutableStateOf(1)
        private set

    /** 选中查看的周（由 setViewingWeek / shiftWeek 修改） */
    var viewingWeek by mutableStateOf(1)
        private set

    init {
        bootstrap()
    }

    // ── 启动 ────────────────────────────────────────────────────────────

    private fun bootstrap() {
        /*
         * 先看缓存是不是「旧算法」留下的。
         *
         * 周次错位（教务周从周日起、App 从周一起）修好之后，
         * 已经缓存的数据仍然是错位的 —— 不清掉的话用户升级后
         * 看到的还是老样子，会以为没修。版本号对不上就丢弃，走重新同步。
         */
        store.dropStaleSessionCache()
        store.dropStaleGradeCache()

        // 日程独立读回：不参与上面的失效逻辑，也不需要登录态。
        // 用户手输的数据不该因为课表缓存版本升级而消失。
        val savedAgenda = agendaStore.load()
        if (savedAgenda.isNotEmpty()) state = state.copy(agenda = savedAgenda)

        /*
         * 考试缓存单独读回，不套用课表的版本失效。
         *
         * 两者的解析字段完全不同（考试是 course/date/time/room/seat/type 全字符串），
         * 课表算法改一次就顺手清掉考试缓存没有道理，而且会逼用户重新联网一次。
         */
        readExamCache().let { if (it.isNotEmpty()) state = state.copy(exams = it) }

        /*
         * 学籍（班级 / 年级 / 专业）单独读回。
         *
         * 和日程、考试同一个道理：它不属于课表缓存，不该被「课表算法升级」清掉，
         * 也不依赖登录态 —— 断网时班级作业面板照样要能预填班级名。
         */
        readStudentCardCache()?.let { state = state.copy(studentCard = it) }

        /*
         * 班级日程：先用本地缓存把首页那行画出来，再决定要不要联网。
         *
         * 顺序不能反 —— 如果先联网再画，冷启动时那一行会空一下。
         * syncClassPlan 里自带 24 小时节流，所以每次启动都调是安全的。
         */
        rebuildClassPlanHint()
        syncClassPlan()

        /*
         * 学籍：启动时顺手补一次。
         *
         * 只在「还没拉到」时真请求，所以正常情况下开机就是空跑一跳 ——
         * 但不能只挂在手动同步上：用户可能一整个学期都不点同步，
         * 班级日程的密钥就一直是空的。
         */
        ensureStudentCard()

        /*
         * 班级清单：本机维护的所有份。列表为空表示还没建过，面板会引导他建。
         *
         * 编辑目标默认指向第一份 —— 学委点进「班级日程」就该看到自己最近在弄的东西，
         * 而不是先挑一份（只有一份时那一屏纯属浪费）。
         */
        classPlanStore.loadAll().let { list ->
            if (list.isNotEmpty()) {
                state = state.copy(classPlans = list, editingPlanKey = list.first().key)
            }
        }
        refreshPublishedPlanKeys()

        // 天气和登录态无关：没登录也照样显示，先摆缓存再按节流决定要不要联网
        loadWeather()

        val cachedSessions = readSessionCache()
        val cachedSemester = readSemesterCache()
        if (cachedSessions.isNotEmpty() && cachedSemester != null) {
            val week = cachedSemester.weekOf(LocalDate.now()).coerceIn(1, cachedSemester.totalWeeks)
            currentWeek = week
            viewingWeek = week
            state = state.copy(
                sessions = cachedSessions,
                semester = cachedSemester,
                phase = Phase.Loading,
                // 电费也先把缓存摆上来，别一进页面是「--」再跳；
                // 明细一起恢复，否则折线图和「每日明细」要等同步完才出现
                power = cachedPowerOrNull(),
                powerDaily = cachedPowerDailyOrNull(),
                // 成绩也先摆缓存，别一进页面是空的
                grades = cachedGradesOrEmpty(),
                levels = cachedLevelsOrEmpty(),
                fitness = cachedFitnessOrEmpty(),
                // 缓存里的周也算「已到达」，否则断网时每周都显示「还在路上」一直转圈
                loadedWeeks = (1..cachedSemester.totalWeeks).toSet(),
                progressDone = cachedSemester.totalWeeks,
                // +1：fetchWeeksExcept 会多抓一周（最后一周的周日落在教务下一周）
                progressTotal = cachedSemester.totalWeeks + 1,
            )
            /*
             * 一打开就按缓存重排提醒，**不等联网同步**。
             *
             * App 被系统强停后（ColorOS 后台清理常见），闹钟全没了；用户打开 App
             * 时如果网络不好或会话过期，同步那一步就走不到 reschedule —— 提醒继续断着。
             * 先拿缓存排上，同步成功后会再排一次。
             */
            reschedule(cachedSemester, cachedSessions)
        }
        viewModelScope.launch {
            /*
             * 先查更新，再管登录态。
             *
             * 踩过的坑：原来把它放在这个 launch 的最后，前面有两个 return@launch
             * （未登录、教务 ping 失败），结果只要停在登录页就**永远不查更新** ——
             * 用户看到的现象是「有新版本但一点提示都没有」。
             *
             * 检查更新打的是 api.github.com，跟学校登录态没有任何关系，
             * 放在最前面才不会被打断。
             */
            checkUpdateSilently()

            val loggedIn = Net.cookies.has("jw.gzucm.edu.cn")
            if (!loggedIn) {
                state = state.copy(phase = Phase.NeedLogin)
                return@launch
            }
            val ok = withContext(Dispatchers.IO) { runCatching { Jw.ping() }.getOrDefault(false) }
            if (!ok) {
                // 会话可能死了。**这里不再直接写提示** —— 只做一次真实同步尝试，
                // 由 doSync 的失败分支决定要不要提示（一天最多一次）。
                state = state.copy(phase = if (cachedSessions.isEmpty()) Phase.NeedLogin else Phase.Ready)
                if (cachedSessions.isNotEmpty()) syncQuietly()
                return@launch
            }
            state = state.copy(phase = Phase.Ready)
            syncQuietly()
            loadPowerQuietly()
        }
    }

    /** 登录成功后由 WebView 回调：注入两套 cookie 并开始同步 */
    /**
     * 教务会话一就绪就调用：立刻开始抓课表。
     *
     * 登录页在跳一卡通 SSO 后还要等最多 8 秒兜底，那 8 秒原本是纯等待。
     * 提前在这里启动，课表和一卡通的等待就并行了。
     */
    fun onJwReady(jwCookies: Map<String, String>) {
        if (jwCookies.isEmpty()) return
        Net.cookies.seed("jw.gzucm.edu.cn", jwCookies)
        // 教务已经认过了，那条「会话过期」的说明可以撤了
        reloginNotice = null

        /*
         * 「点同步 → 掉登录 → 重登」这条路上的补同步，**必须在这里做**。
         *
         * 2026-11-20 致辉实测：点同步后登录页弹出、一秒后自己消失。
         * 那一秒就是 CAS 还活着、直接放行 —— 而 `onJwReady` 一改 phase，
         * 登录页当场被组合移除、WebView 跟着销毁，后面 Ecard 阶段的
         * onPageFinished 和 8 秒兜底都不会再跑，**`onLoggedIn` 根本不会被调用**。
         *
         * 所以补同步只写在 onLoggedIn 里等于没写：用户会看到网页闪一下、
         * 回到课表还是旧数据，点了半天像什么都没发生。
         */
        val resumeSync = syncAfterRelogin
        syncAfterRelogin = false

        if (state.phase != Phase.Ready) {
            state = state.copy(phase = Phase.Ready, message = null)
        }
        // 已经在同步就别重复开一份
        if (!state.syncing) {
            /*
             * 补同步走**静默**（manual = false），不能按手动跑。
             *
             * 手动同步撞上 401 会再次触发「直接跳登录页」；补同步如果再失败，
             * 就变成「登录页 → 自动关 → 又弹登录页」的无限循环。
             * 静默失败只是留一句提示，用户想再试可以自己点同步。
             */
            if (state.sessions.isEmpty() || resumeSync) syncQuietly()
        }
    }

    fun onLoggedIn(jwCookies: Map<String, String>, ecardCookies: Map<String, String>) {
        if (jwCookies.isNotEmpty()) Net.cookies.seed("jw.gzucm.edu.cn", jwCookies)
        if (ecardCookies.isNotEmpty()) Net.cookies.seed("ecard.gzucm.edu.cn", ecardCookies)
        state = state.copy(phase = Phase.Ready, message = null)
        loginExpired = false
        reloginNotice = null
        // 从「点同步发现过期」跳过来的：把那次没做完的同步补上，用户不用再点一次
        val resumeSync = syncAfterRelogin
        syncAfterRelogin = false
        /*
         * 登录成功后再查一次更新。
         *
         * checkUpdateSilently 内部有「今天查过就跳过」的判断，
         * 所以这里补调不会重复请求；但如果是「启动时没登录」的情况，
         * 这一次补调就是唯一的检查机会 —— 少了它，用户从登录页进来后
         * 一整天都看不到新版本提示。
         */
        checkUpdateSilently()
        // 课表可能已经在 onJwReady 时抓过了，别重复；
        // 但「重登补同步」这条路上即使有缓存也要刷一次 —— 那正是用户点同步的目的
        if (!state.syncing && (state.sessions.isEmpty() || resumeSync)) syncQuietly()
        // 电费：登录后打个待刷新标记，用户进电费页时弹窗同步一次
        if (ecardCookies.isNotEmpty()) powerDirty = true
    }

    /**
     * 研究生系统登录成功。
     *
     * 和本科的 [onLoggedIn] 有三处不同：
     *   ① 没有一卡通（电费不做）
     *   ② 不设 powerDirty（那套只对一卡通有意义）
     *   ③ 要清掉本科残留的课表缓存 —— 两个学段的课表完全是两套数据，
     *      不清的话研究生用户会先看到一堆本科课程
     */
    fun onGraduateLoggedIn(yjsCookies: Map<String, String>) {
        if (yjsCookies.isNotEmpty()) {
            Net.cookies.seed("yjsglxt.gzucm.edu.cn", yjsCookies)
        }
        // 切学段必须清缓存，否则会先闪一下另一边的课表
        clearScheduleCacheForCampusSwitch(Campus.GRADUATE)
        state = state.copy(
            phase = Phase.Ready,
            message = null,
            sessions = emptyList(),
            semester = null,
            grades = emptyList(),
            levels = emptyList(),
            fitness = emptyList(),
            exams = emptyList(),
        )
        loginExpired = false
        reloginNotice = null
        checkUpdateSilently()
        if (!state.syncing) syncQuietly()
    }

    /**
     * 切学段：清掉课表 / 成绩缓存，保留界面设置（主题、背景图这些与学段无关）。
     *
     * **必须清**：本科的 CourseSession 和研究生的是两套字段，
     * 混在一起显示会出现「课名对、教室错」这种查不出来的问题。
     */
    private fun clearScheduleCacheForCampusSwitch(target: Campus) {
        if (store.campus == target) return
        store.campus = target
        store.cachedSessions = null
        // 考试跟着课表走：研究生没有对应接口，留着本科的会串学段
        store.cachedExams = null
        store.cachedSemester = null
        store.semesterCode = null
        store.cachedGrades = null
        store.cachedLevels = null
        store.cachedFitness = null
        // 研究生学期码和本科的格式完全不同，单独清
        if (target == Campus.UNDERGRAD) store.graduateTermCode = null
    }

    /**
     * 退出登录。
     *
     * ⚠️ **必须同时清 WebView 的 Cookie**（致辉 2026-11-01 报的 bug）。
     *
     * 之前只做 `Net.cookies.clear()` —— 那只清了 App 自己内存和 prefs 里的
     * 一份拷贝，**WebView 里真实的 Cookie 一个都没动**。
     *
     * 后果：退出后点登录，WebView 打开 `/new/ssoLogin`，浏览器自己带着
     * 上一次的 CAS 会话（`CASTGC`），CAS 直接给回原账号的 ticket，
     * 于是「退出登录」变成「重新登录同一个账号」——
     * 根本没法切换账号。用户只会觉得这个按钮坏了。
     *
     * 三处都要清，缺一处都还有残留：
     *   ① WebView 的 CookieManager（真正的会话所在）
     *   ② WebView 的本地存储 / 表单缓存（有的页面把登录态存 localStorage）
     *   ③ App 自己维护的 Cookie 副本 + 各种缓存
     */
    fun logout() {
        /*
         * 先等 Cookie 真正删掉，再切到登录页。
         *
         * `removeAllCookies` 是**异步**的。如果这边同步把 phase 改成
         * NeedLogin，Compose 下一次重组就会创建登录页的 WebView，
         * 而那时删除可能还没执行完 —— WebView 带着旧 Cookie 打开 ssoLogin，
         * CAS 直接发回原账号的 ticket，白清一场。
         *
         * 所以用 suspendCancellableCoroutine 等回调，删干净了再切。
         */
        viewModelScope.launch {
            val done = suspendCancellableCoroutine<Unit> { cont ->
                android.webkit.CookieManager.getInstance().removeAllCookies {
                    cont.resume(Unit)
                }
            }
            // 等到了就往下走（异常也继续，不能因为清理失败把用户困在已登录状态）
            runCatching { done }

            android.webkit.CookieManager.getInstance().flush()
            runCatching { android.webkit.WebStorage.getInstance().deleteAllData() }

            Net.cookies.clear()
            store.cachedSessions = null
            store.cachedExams = null
            store.cachedSemester = null
            store.cachedPower = null
            store.cachedPowerDaily = null
            store.cachedGrades = null
            store.cachedLevels = null
            store.cachedFitness = null
            // 日程是用户自建数据，退出登录不该把它清掉；从独立存储原样带回来
            state = UiState(phase = Phase.NeedLogin, agenda = agendaStore.load())
            // 主动退出登录是用户自己的决定，别把「会话过期」的解释挂在这上面
            reloginNotice = null
            syncAfterRelogin = false
        }
    }

    /**
     * 从设置页点「重新登录」：清会话但保留缓存课程表，回到登录页。
     *
     * 同样要清 WebView 的 Cookie，否则重登会直接回到原账号。
     * 这里不清课表缓存 —— 用户可能只是想换个账号看，旧数据留着能先垫着。
     */
    /**
     * 「重新登录」：**保留 cookie**，只回到登录页重新走一次认证。
     *
     * ## ⚠️ 这里和 [logout] 是两件事，不能共用清理逻辑
     *
     * 致辉 2026-11-02 报的回归：
     * 修「退出登录切不了账号」那个 bug 时，我把清 cookie 的代码
     * 也顺手加到了 `reopenLogin` 里 —— 于是「重新登录」变成了
     * 「把登录信息删掉，手动重输账号密码」。
     *
     * 两个按钮的语义完全不同：
     *
     * | | 清 WebView cookie | 清 App cookie | 清课表缓存 | 用户要做什么 |
     * |---|---|---|---|---|
     * | **重新登录** | ❌ 不清 | ❌ 不清 | ❌ 保留 | 什么都不用输，WebView 带着原会话进去，CAS 直接放行 |
     * | **退出登录** | ✅ 清 | ✅ 清 | ✅ 清 | 输入新账号 |
     *
     * 「重新登录」的使用场景是：会话过期了、或者同步出错了，
     * 想让用户重新走一遍认证 —— 而 WebView 里的 CAS 会话通常还活着，
     * 进去一点就回来了，根本不用输密码。**清掉就白费了这个便利。**
     *
     * 所以这里只切 phase，不动任何会话和缓存。
     * 课表缓存也留着：重登期间界面还能显示旧数据，不是白屏。
     */
    fun reopenLogin() {
        state = state.copy(phase = Phase.NeedLogin)
    }

    /**
     * 设置页那个「重新登录」按钮的唯一入口。
     *
     * ## 为什么还要包一层
     *
     * [reopenLogin] 本身是对的（只切 phase），但**调用方**在 RootScreen 里
     * 写成了 `vm.logout(); vm.reopenLogin()` —— 先清干净、再回登录页，
     * 等于把「重新登录」做成了「退出登录」。致辉 2026-11-02 又报了一次。
     *
     * 光靠注释挡不住下一次。所以把语义收到 ViewModel 里：
     * 调用方只有这一个方法可调，方法内部不碰任何会话和缓存，
     * 想让「重新登录」清 cookie 就得改这里 —— 那时上面的注释和
     * `LoginReopenTest` 会同时拦一下。
     */
    fun relogin() = reopenLogin()

    /**
     * 「稍后再登」：从自动弹出的登录页退回主界面，保留缓存课表。
     *
     * 为什么需要这个出口：点同步被直接送到登录页时，如果用户当时没空扫码，
     * 他至少得能回去看已经缓存的课表 —— 否则这个「贴心」的设计就变成
     * 「点一下同步，App 卡在登录页出不去」。
     *
     * 没有任何缓存时不给这个入口（回到主界面也是白屏），由调用方传 null。
     */
    fun skipRelogin() {
        reloginNotice = null
        // 用户决定先不登，那次补同步也就跟着作废
        syncAfterRelogin = false
        if (state.sessions.isEmpty()) return
        state = state.copy(phase = Phase.Ready)
    }

    // ── 同步 ────────────────────────────────────────────────────────────

    fun sync() {
        if (state.syncing || syncJob?.isActive == true) return
        // 手动同步 = 课表 + 电费一起刷；电费要等用户进电费页才能取（会话在 WebView 里），
        // 所以这里只打标记，不做无用的后台请求。研究生没有电费，不打标记
        if (store.campus != Campus.GRADUATE) powerDirty = true
        ensureStudentCard()
        launchSync(manual = true)
    }

    /** 从本地读学籍缓存；没有（或者存坏了）返回 null */
    private fun readStudentCardCache(): com.gzucm.pocket.core.StudentCard? {
        val raw = store.studentCardJson ?: return null
        return runCatching {
            Net.json.decodeFromString(com.gzucm.pocket.core.StudentCard.serializer(), raw)
        }.getOrNull()
    }

    /**
     * 保证本地有一份学籍缓存。
     *
     * **只在还没有的时候才真请求**：学籍一学期才变一次，每次同步都拉
     * 126KB 的 HTML 没有意义，而且失败时（没登录、会话过期）还会白等一次超时。
     * 取到后同时更新缓存和 state —— 班级作业面板靠它预填班级名。
     *
     * 失败静默：拿不到就是拿不到，用户照样能手动填班级名，
     * 不能因为学籍拉失败就给同步弹一个错误提示。
     */
    private fun ensureStudentCard() {
        if (state.studentCard != null) return
        viewModelScope.launch {
            val card = withContext(Dispatchers.IO) {
                runCatching { Jw.fetchStudentCard() }.getOrNull()
            } ?: return@launch
            runCatching {
                store.studentCardJson =
                    Net.json.encodeToString(com.gzucm.pocket.core.StudentCard.serializer(), card)
            }
            state = state.copy(studentCard = card)
            // 新拿到的班级写进同步存储：它是云端查询的主索引（契约 §1）
            classSyncStore.className = card.classKey
            syncClassPlan()
        }
    }

    /**
     * 同时只跑一份同步（[syncJob] 声明在 init 之前，见那边的说明）。
     *
     * 原来 bootstrap、onJwReady、手动刷新、切回前台都能各自起一份 doSync，
     * 两份并发时都在写 state 和缓存，后完成的覆盖先完成的；进度条也会来回跳。
     * 已经在跑就直接返回 —— 手动刷新时正在跑的那份结束后结果一样是新的。
     */
    private fun launchSync(manual: Boolean) {
        if (syncJob?.isActive == true) return
        syncJob = viewModelScope.launch {
            if (store.campus == Campus.GRADUATE) doSyncGraduate(manual) else doSync(manual)
        }
    }

    private fun syncQuietly() = launchSync(manual = false)

    // ── 研究生同步 ────────────────────────────────────────────────
    /*
     * 研究生系统和本科完全两套：
     *   · 课表一次请求拿全学期（本科要按周抓 19 次）
     *   · 学期码是整数 48（本科是 202601）
     *   · 没有电费（一卡通是另一套登录，暂不做）
     *   · 成绩分「学位课 / 非学位课」
     *
     * 所以单独一个函数，不去改 doSync 里那一大堆本科逻辑 ——
     * 那边有按周抓取、进度条、断点续抓，研究生这边一步就完事，
     * 硬塞进同一个函数只会让两边都难读。
     */
    private suspend fun doSyncGraduate(manual: Boolean = false) {
        /*
         * 研究生课表现在是**逐周抓**（致辉 2026-11-01 定）。
         *
         * 为什么：全学期一次拿回来时，同一格里混着 20 周的记录，
         * 而学校的数据有残缺 —— 有的课在某一节次上漏写了周次行，
         * 解析时拼块就会断。逐周查由接口自己筛，缺行问题自然消失。
         *
         * 代价：20 次请求约 600ms。所以进度条要跟着走，
         * 否则用户看着转圈不知道在干什么。
         */
        val weeks = Yjs.DEFAULT_WEEKS
        state = state.copy(
            syncing = true,
            message = null,
            progressDone = 0,
            progressTotal = weeks,
        )

        val result = try {
            // ① 学期先拿到（只有一次请求），顺序的
            val term = withContext(Dispatchers.IO) {
                Yjs.fetchCurrentTerm() ?: error("拿不到研究生学期列表")
            }
            // ② 逐周抓，进度实时更新
            val all = ArrayList<CourseSession>(256)
            var failedWeeks = 0
            var authError: Throwable? = null
            for (w in 1..weeks) {
                /*
                 * 逐周容错（和本科 Jw.fetchWeeksExcept 对齐）。
                 *
                 * 原来任何一周解析失败（某周数据格式异常）就整次同步报错，
                 * 其它 19 周白抓。现在只有登录失效才中止 —— 那种情况后面每周都会失败，
                 * 没必要再打 19 次请求。
                 */
                val one = withContext(Dispatchers.IO) {
                    runCatching { Yjs.fetchWeek(term.code, w) }
                }
                one.onSuccess { all.addAll(it) }
                    .onFailure { e ->
                        failedWeeks++
                        android.util.Log.w("Sync", "研究生第 $w 周抓取失败", e)
                        if (e is NetError.Unauthorized) authError = e
                    }
                authError?.let { throw it }
                state = state.copy(progressDone = w)
            }
            if (failedWeeks > 0) android.util.Log.w("Sync", "研究生课表 $failedWeeks 周失败，已跳过")
            // 跨周去重
            val seen = HashSet<String>(all.size)
            val sessions = all.filter { s ->
                seen.add(
                    "${s.name}|${s.week}|${s.weekday}|${s.startUnit}|${s.endUnit}|" +
                        "${s.room}|${s.teachers}",
                )
            }
            if (sessions.isEmpty()) error("课表是空的")
            // ③ 从课表日期反推学期起点
            val semester = Yjs.buildSemester(term, sessions)
            Result.success(Triple(term, semester, sessions))
        } catch (e: Exception) {
            Result.failure(e)
        }

        result.onSuccess { (term, semester, sessions) ->
            store.campus = Campus.GRADUATE
            /*
             * ⚠️ **绝对不要写 store.semesterCode**（致辉 2026-11-01 报的 bug）。
             *
             * semesterCode 是**本科专用**字段：doSync 里拿它去请求
             * `/new/xlxx/data?xnxqdm=$code`，期望形如 "202601"。
             *
             * 之前这里写了一句 `store.semesterCode = term.code`，
             * 把研究生的 "48" 塞了进去。之后切回本科时：
             *   val code = store.semesterCode ?: Jw.guessSemesterCode()
             *   → "48"（不是 null，兜底永远不触发）
             *   → Jw.fetchSemester("48") 必然拿不到校历
             *   → 报「无法获取教学周历」，本科课表整个空的
             *
             * 研究生的学期码有自己的字段 graduateTermCode，别混用。
             */
            store.graduateTermCode = term.code
            store.cachedSemester = Net.json.encodeToString(Semester.serializer(), semester)
            store.cachedSessions = Net.json.encodeToString(
                kotlinx.serialization.builtins.ListSerializer(CourseSession.serializer()),
                sessions,
            )
            // 原来研究生同步完从不排提醒 —— 研究生开着「上课提醒」也收不到
            reschedule(semester, sessions)

            val week = semester.weekOf(LocalDate.now()).coerceIn(1, semester.totalWeeks)
            currentWeek = week
            viewingWeek = week
            state = state.copy(
                phase = Phase.Ready,
                semester = semester,
                sessions = sessions,
                syncing = false,
                loadedWeeks = (1..semester.totalWeeks).toSet(),
                progressDone = 1,
                progressTotal = 1,
                lastSync = System.currentTimeMillis(),
                syncResultSeq = state.syncResultSeq + 1,
                syncResultText = if (manual) "已同步 ${sessions.size} 节课" else null,
                message = null,
            )
        }.onFailure { e ->
            state = state.copy(syncing = false, progressDone = 0, progressTotal = 0)
            val msg = when {
                e is NetError.Unauthorized -> {
                    /*
                     * 研究生系统一旦 401，后面 19 个请求全会一样失败 ——
                     * 所以这里不像本科那样「有缓存就先留在主界面」，
                     * 直接进登录页（有缓存时给「稍后再登」的出口，见 skipRelogin）。
                     */
                    reloginNotice = "研究生系统登录状态已过期"
                    syncAfterRelogin = true
                    state = state.copy(phase = Phase.NeedLogin)
                    "研究生系统登录已过期"
                }
                else -> com.gzucm.pocket.core.UserMessage.of(e)
            }
            if (manual) {
                state = state.copy(syncResultSeq = state.syncResultSeq + 1, syncResultText = msg)
            }
        }
    }

    /**
     * @param manual 用户主动点的同步。只有手动同步才弹「成功 / 失败」提示 ——
     *   后台静默同步（冷启动、切前台）弹提示会变成噪音。
     */
    private suspend fun doSync(manual: Boolean = false) {
        state = state.copy(syncing = true, message = null)

        // ── 第一阶段：学期周历 + 当前周 ──────────────────────────────
        // 只等 2 次往返就能出画面。全量 20 周在好网络下也才 0.2 秒，
        // 但弱网时每次可能 1 秒以上，先给当前周能把白屏从几十秒压到几秒。
        val first = withContext(Dispatchers.IO) {
            runCatching {
                /*
                 * 校验缓存里的学期码格式，不合格就当没有。
                 *
                 * 本科学期码固定是「4 位年份 + 01/02」（如 202601）。
                 * 研究生的（如 "48"）如果因为 bug 混进来，
                 * 直接拿去请求校历必然失败，而且失败后还会拿它去算
                 * 「上一个学期」，越算越离谱 —— 最后报「无法获取教学周历」。
                 *
                 * 这一步是给已经中毒的用户兜底：升级后即使存储里还是坏值，
                 * 也会被这里拦下来，改用按日期猜的正确值。
                 */
                val code = Jw.resolveSemesterCode(store.semesterCode)
                val semester = Jw.fetchSemester(code)
                    ?: Jw.fetchSemester(Jw.previousSemesterCode(code))
                    ?: error("无法获取教学周历")
                val week = semester.weekOf(LocalDate.now()).coerceIn(1, semester.totalWeeks)
                val weekSessions = runCatching { Jw.fetchWeek(semester, week) }.getOrDefault(emptyList())
                Triple(semester, week, weekSessions)
            }
        }

        val semester = first.getOrElse { e ->
            val today = LocalDate.now().toString()
            // 「登录已过期」这类要你动手的提示，一天一次就够。
            // 以前每次冷启动都会看到同一句话，纯噪音。
            val authIssue = com.gzucm.pocket.core.UserMessage.isAuthIssue(e)
            if (authIssue) loginExpired = true

            /*
             * 手动同步撞上会话过期 → 直接把登录页摆出来（2026-11-20 致辉提的）。
             *
             * 原来的路径是：提示「登录已过期」→ 用户点「去登录」→ 设置面板 →
             * 滚到「重新登录」→ 再点。四步，而且很多同学**不敢点**那个按钮，
             * 怕点完要重新扫码 / 重输账号。
             *
             * 实际上 reopenLogin 只切页面、不碰 cookie，WebView 带着 CAS 会话进去
             * 通常直接就回来了 —— 也就是说这一步本来就是「点一下就好」，
             * 那就不该让用户去猜。点同步 = 想拿最新数据，直接把他送到该去的地方。
             *
             * 只在有缓存课表时才这么干：没缓存时 phase 本来就是 NeedLogin，不必特别处理。
             */
            val jumpToLogin = authIssue && manual && state.sessions.isNotEmpty()
            if (jumpToLogin) {
                reloginNotice = "同步时发现登录状态已过期"
                // 登录成功回来后把这次没做完的同步补上
                syncAfterRelogin = true
            }

            val showIt = if (!authIssue) true else store.loginExpiredNotifiedDate != today
            if (authIssue && showIt) store.loginExpiredNotifiedDate = today

            val sem = state.semester
            state = state.copy(
                syncing = false,
                // 已经跳到登录页了就不必再在首页留一句同样的话
                message = if (showIt && !jumpToLogin) describe(e) else state.message,
                syncResultSeq = if (manual) state.syncResultSeq + 1 else state.syncResultSeq,
                syncResultText = if (manual) "同步失败：${describe(e)}" else state.syncResultText,
                phase = when {
                    state.sessions.isEmpty() || jumpToLogin -> Phase.NeedLogin
                    /*
                     * 人已经在登录页就别把他拽回主界面。
                     *
                     * 场景：自动跳到登录页之后，某次后台静默同步也失败了
                     * （manual = false），走原来的分支会把 phase 写回 Ready ——
                     * 用户正对着登录页输密码，页面自己关了。
                     */
                    state.phase == Phase.NeedLogin -> Phase.NeedLogin
                    else -> Phase.Ready
                },
                // 失败时别让课表停在「加载中」：有缓存就按缓存全量展示
                loadedWeeks = if (sem != null && state.sessions.isNotEmpty())
                    (1..sem.totalWeeks).toSet() else state.loadedWeeks,
            )
            return
        }.first

        first.onSuccess { (sem, week, weekSessions) ->
            currentWeek = week
            viewingWeek = week
            state = state.copy(
                phase = Phase.Ready,
                semester = sem,
                sessions = weekSessions.sortedWith(compareBy({ it.week }, { it.weekday }, { it.startUnit })),
                loadedWeeks = setOf(week),
                progressDone = 1,
                progressTotal = sem.totalWeeks + 1,
                syncing = true,
            )
        }

        // ── 第二阶段：补齐其余周，边到边更新进度 ────────────────────
        val week = first.getOrThrow().second
        /*
         * 进度回调来自 5 个并发的 IO 线程。原来在回调里直接 `state = state.copy(...)`：
         *   · 多线程同时对整个 UiState 做读改写，后写的会把先写的、以及主线程同期写的
         *     其它字段（电费、成绩、message）整体回滚
         *   · 在非主线程写 Compose state，快照系统虽不崩，但会和主线程的写入冲突
         * 改成只往一个原子计数器里写，主线程按节拍读出来更新进度条。
         */
        val progress = java.util.concurrent.atomic.AtomicInteger(0)
        val ticker = viewModelScope.launch {
            while (true) {
                val d = progress.get()
                if (d + 1 > state.progressDone) state = state.copy(progressDone = d + 1)
                kotlinx.coroutines.delay(120)
            }
        }
        val restSessions = try {
            withContext(Dispatchers.IO) {
                runCatching {
                    Jw.fetchWeeksExcept(semester, week) { done ->
                        progress.accumulateAndGet(done) { a, b -> maxOf(a, b) }
                    }
                }.getOrDefault(emptyList())
            }
        } finally {
            ticker.cancel()
        }

        val sessions = withContext(Dispatchers.Default) {
            (first.getOrThrow().third + restSessions)
                .sortedWith(compareBy({ it.week }, { it.weekday }, { it.startUnit }))
        }

        if (sessions.isNotEmpty()) {
            store.semesterCode = semester.code
            store.cachedSemester = Net.json.encodeToString(Semester.serializer(), semester)
            store.cachedSessions = Net.json.encodeToString(
                kotlinx.serialization.builtins.ListSerializer(CourseSession.serializer()),
                sessions,
            )
            store.lastSyncAt = System.currentTimeMillis()
            store.loginExpiredNotifiedDate = null
            loginExpired = false
            reloginNotice = null
            syncAfterRelogin = false
            // 课表变了就重排提醒（未来 7 天，代价很小）
            reschedule(semester, sessions)
            state = state.copy(
                phase = Phase.Ready,
                semester = semester,
                sessions = sessions,
                loadedWeeks = (1..semester.totalWeeks).toSet(),
                syncing = false,
                lastSync = store.lastSyncAt,
                message = null,
                syncResultSeq = if (manual) state.syncResultSeq + 1 else state.syncResultSeq,
                syncResultText = if (manual) "同步完成 · 共 ${sessions.size} 节课" else state.syncResultText,
            )

            /*
             * 顺带抓考试安排。
             *
             * 单独一个 try：考试接口挂掉不能连累课表 —— 课表是本命功能，
             * 考试是附加信息，而且很多学期前几周根本没有考试数据。
             *
             * 抓不到就保留上一次的缓存，不写空值覆盖（否则用户刚看到的考试
             * 会因为一次网络抖动集体消失）。
             */
            withContext(Dispatchers.IO) {
                runCatching { Jw.fetchExams(semester.code) }
            }.onSuccess { exams ->
                if (exams.isNotEmpty()) {
                    store.cachedExams = Net.json.encodeToString(
                        kotlinx.serialization.builtins.ListSerializer(ExamItem.serializer()),
                        exams,
                    )
                    state = state.copy(exams = exams)
                }
            }
        } else {
            state = state.copy(
                syncing = false,
                message = if (state.sessions.isEmpty()) "没能拿到课表数据，稍后可以下拉重试" else null,
                syncResultSeq = if (manual) state.syncResultSeq + 1 else state.syncResultSeq,
                syncResultText = if (manual) "同步失败：没拿到课表数据" else state.syncResultText,
            )
        }
    }

    private fun readSessionCache(): List<CourseSession> {
        val raw = store.cachedSessions ?: return emptyList()
        return runCatching { Net.json.decodeFromString<List<CourseSession>>(raw) }.getOrDefault(emptyList())
    }

    private fun readSemesterCache(): Semester? {
        val raw = store.cachedSemester ?: return null
        return runCatching { Net.json.decodeFromString(Semester.serializer(), raw) }.getOrNull()
    }

    private fun readExamCache(): List<ExamItem> {
        val raw = store.cachedExams ?: return emptyList()
        return runCatching { Net.json.decodeFromString<List<ExamItem>>(raw) }.getOrDefault(emptyList())
    }

    // ── 成绩 ────────────────────────────────────────────────────────────

    fun loadGrades(force: Boolean = false) {
        if (state.gradesLoading) return
        // 有缓存且是 10 分钟内抓的就不再拉；否则后台刷新一次（界面已有缓存，不会白屏）
        val fresh = System.currentTimeMillis() - store.gradesCheckedAt < 10 * 60_000L
        if (!force && state.grades.isNotEmpty() && fresh) return
        // 三个接口顺序拉，分批上报进度，别让用户对着一个不动的转圈干等
        state = state.copy(
            gradesLoading = true, message = null,
            gradesDone = 0, gradesTotal = 3, gradesStage = "课程成绩",
        )
        /*
         * 研究生走自己的接口（2026-11-06 修）。
         *
         * 原来这里不分学段，研究生也去调本科的 Jw.fetch*。两套系统 cookie 不通用，
         * 本科接口必然 Unauthorized → 下面把 loginExpired 置 true → 成绩页显示
         * 「登录状态掉了」。而 Yjs.fetchGrades 早就写好了，只是没人调用。
         * 研究生没有等级考试 / 体测这两项，只拉一次。
         */
        val graduate = store.campus == Campus.GRADUATE
        if (graduate) state = state.copy(gradesTotal = 1)

        viewModelScope.launch {
            /*
             * 进度在主线程更新。原来是在 IO 线程里直接 `state = state.copy(...)`：
             * copy 基于读到的旧快照，主线程这期间写的 power / message 等字段会被整体回滚。
             */
            val r = runCatching {
                if (graduate) {
                    val grades = withContext(Dispatchers.IO) { Yjs.fetchGrades() }
                    Triple(grades, emptyList<LevelGrade>(), emptyList<FitnessGrade>())
                } else {
                    val grades = withContext(Dispatchers.IO) { Jw.fetchCourseGrades() }
                    state = state.copy(gradesDone = 1, gradesStage = "等级成绩")
                    val levels = withContext(Dispatchers.IO) {
                        runCatching { Jw.fetchLevelGrades() }.getOrDefault(emptyList())
                    }
                    state = state.copy(gradesDone = 2, gradesStage = "体测成绩")
                    val fitness = withContext(Dispatchers.IO) {
                        runCatching { Jw.fetchFitness() }.getOrDefault(emptyList())
                    }
                    Triple(grades, levels, fitness)
                }
            }
            r.onSuccess { (g, l, f) ->
                // 落盘：下次冷启动先拿缓存垫着，不用等网络
                if (g.isNotEmpty()) {
                    store.cachedGrades = Net.json.encodeToString(
                        kotlinx.serialization.builtins.ListSerializer(CourseGrade.serializer()), g,
                    )
                    store.cachedLevels = Net.json.encodeToString(
                        kotlinx.serialization.builtins.ListSerializer(LevelGrade.serializer()), l,
                    )
                    store.cachedFitness = Net.json.encodeToString(
                        kotlinx.serialization.builtins.ListSerializer(FitnessGrade.serializer()), f,
                    )
                    store.gradesCheckedAt = System.currentTimeMillis()
                }
                state = state.copy(
                    grades = g, levels = l, fitness = f,
                    gradesLoading = false,
                    gradesDone = state.gradesTotal, gradesStage = null,
                )
            }.onFailure { e ->
                // 进成绩页也会单独打三个请求；这里失败同样说明会话可能掉了，
                // 不能只靠课表同步那条路判断
                if (com.gzucm.pocket.core.UserMessage.isAuthIssue(e)) loginExpired = true
                state = state.copy(
                    gradesLoading = false, message = describe(e), gradesStage = null,
                )
            }
        }
    }

    // ── 电费 ────────────────────────────────────────────────────────────

    fun loadPower(force: Boolean = false) {
        if (state.powerLoading) return
        if (!force && state.power != null) return
        // 没有一卡通会话时，OkHttp 必然被重定向到 SSO，白跑一趟还会报错。
        // 数据只能靠电费页的弹窗同步（会话在 WebView 里），这里直接不动。
        if (!Net.cookies.has("ecard.gzucm.edu.cn")) return
        state = state.copy(powerLoading = true)
        viewModelScope.launch { doLoadPower() }
    }

    private fun loadPowerQuietly() {
        if (state.power != null) return
        viewModelScope.launch { doLoadPower() }
    }

    private fun cachedGradesOrEmpty(): List<CourseGrade> =
        store.cachedGrades?.let {
            runCatching { Net.json.decodeFromString<List<CourseGrade>>(it) }.getOrNull()
        } ?: emptyList()

    private fun cachedLevelsOrEmpty(): List<LevelGrade> =
        store.cachedLevels?.let {
            runCatching { Net.json.decodeFromString<List<LevelGrade>>(it) }.getOrNull()
        } ?: emptyList()

    private fun cachedFitnessOrEmpty(): List<FitnessGrade> =
        store.cachedFitness?.let {
            runCatching { Net.json.decodeFromString<List<FitnessGrade>>(it) }.getOrNull()
        } ?: emptyList()

    /** 读本地缓存的每日明细；当日数据每天变，但历史天不会 */
    private fun cachedPowerDailyOrNull(): List<PowerDaily> =
        store.cachedPowerDaily?.let {
            runCatching {
                Net.json.decodeFromString<List<PowerDaily>>(it)
            }.getOrNull()
        } ?: emptyList()

    /** 读本地缓存的电费，启动时先显示它，避免一进来是「--」 */
    private fun cachedPowerOrNull(): PowerInfo? =
        store.cachedPower?.let {
            runCatching { Net.json.decodeFromString(PowerInfo.serializer(), it) }.getOrNull()
        }

    private suspend fun doLoadPower() {
        state = state.copy(powerLoading = true, powerError = null)
        val r = withContext(Dispatchers.IO) {
            runCatching {
                val info = Ecard.fetchPower()
                val daily = runCatching { Ecard.fetchPowerDaily(info) }.getOrDefault(emptyList())
                info to daily
            }
        }
        r.onSuccess { (info, daily) ->
            /**
             * 后台接口（OkHttp）拿不到一卡通会话时会解析出 0。
             *
             * 这个 0 **一律丢弃**，不管之前有没有数据 —— 因为真正的 0 度
             * 由 WebView 那条路（applyEcardSync）写入，那边不做这个保护。
             * 之前只拦「有旧数据时」的 0，新用户第一次进就会看到假 0 度。
             */
            val fakeZero = info.balance <= 0.0
            if (fakeZero) {
                state = state.copy(powerLoading = false, powerError = null)
            } else {
                state = state.copy(power = info, powerDaily = daily, powerLoading = false, powerError = null)
                store.cachedPower = Net.json.encodeToString(PowerInfo.serializer(), info)
            }
        }.onFailure { e ->
            // 不能静默吞掉：用户至少要知道是「没登录」还是「被拦了」还是「网断了」
            state = state.copy(
                powerLoading = false,
                powerError = describePowerError(e),
            )
        }
    }

    /** 把异常翻译成一句人话，显示在电费页 */
    private fun describePowerError(e: Throwable): String = when (e) {
        is com.gzucm.pocket.core.NetError.Unauthorized -> "校园卡登录已失效，点「更新」重新同步"
        else -> com.gzucm.pocket.core.UserMessage.of(e)
    }

    /** WebView 通道拿到的数据，直接落库 */
    fun applyEcardSync(info: PowerInfo, daily: List<PowerDaily>) {
        /*
         * 余额是「未知」（-1）时，只更新房间，不动余额。
         *
         * 场景（致辉 2026-11-01 遇到的）：他在小程序里把房间从 2栋409
         * 换绑成 6栋211，但 App 里余额读不到 → 之前整条数据被丢弃 →
         * 卡片一直显示**换绑前的旧房间号**，看着像 App 坏了。
         *
         * 现在房间和余额解耦：读到房间就先更新房间，余额保持原值。
         */
        if (info.balance < 0) {
            val old = state.power
            state = state.copy(
                power = info.copy(balance = old?.balance ?: 0.0),
                powerLoading = false,
                ecardLoggedIn = true,
            )
            old?.let { o ->
                store.cachedPower = Net.json.encodeToString(
                    PowerInfo.serializer(),
                    info.copy(balance = o.balance),
                )
            }
            return
        }

        // 明细偶尔会取到空（页面还在初始化 / 分页没跑完）。
        // 空的时候保留原有明细，别把缓存里的历史数据清掉。
        val finalDaily = if (daily.isEmpty()) state.powerDaily else daily
        powerDirty = false
        state = state.copy(
            power = info,
            powerDaily = finalDaily,
            powerLoading = false,
            powerError = null,
            // 能拿到数据就说明会话有效
            ecardLoggedIn = true,
        )
        store.cachedPower = Net.json.encodeToString(PowerInfo.serializer(), info)
        if (finalDaily.isNotEmpty()) {
            store.cachedPowerDaily = Net.json.encodeToString(
                kotlinx.serialization.builtins.ListSerializer(PowerDaily.serializer()),
                finalDaily,
            )
        }
        // 顺手算好「可用天数」存下来，每日巡检的 Worker 直接读，不用联网
        val recent = finalDaily.sortedBy { it.date }.takeLast(7)
        val avg = if (recent.isEmpty()) 0.0 else recent.sumOf { it.usage } / recent.size
        store.powerDaysLeft = if (avg > 0.01) (info.balance / avg).toFloat() else -1f
        store.powerCheckedAt = System.currentTimeMillis()
        runCatching { com.gzucm.pocket.core.PowerAlert.ensureChannel(getApplication()) }
    }

    fun clearPowerError() {
        state = state.copy(powerError = null)
    }

    /** WebView 通道失败时也要把原因显示出来 */
    fun showPowerError(msg: String) {
        state = state.copy(powerLoading = false, powerError = msg)
    }

    // ── 周切换 ──────────────────────────────────────────────────────────

    fun goToWeek(week: Int) {
        val max = state.semester?.totalWeeks ?: 20
        viewingWeek = week.coerceIn(1, max)
    }

    fun shiftWeek(delta: Int) = goToWeek(viewingWeek + delta)

    fun backToCurrentWeek() = goToWeek(currentWeek)

    // ── 派生数据 ────────────────────────────────────────────────────────

    fun sessionsOfWeek(week: Int): List<CourseSession> = state.sessions.filter { it.week == week }

    fun todaySessions(): List<CourseSession> {
        val today = LocalDate.now()
        val sem = state.semester ?: return emptyList()
        val wd = today.dayOfWeek.value
        return state.sessions
            .filter { it.week == currentWeek && it.weekday == wd }
            .sortedBy { it.startUnit }
    }

    private fun describe(e: Throwable): String = com.gzucm.pocket.core.UserMessage.of(e)
}
