package com.gzucm.pocket.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gzucm.pocket.Graph
import com.gzucm.pocket.core.CourseGrade
import com.gzucm.pocket.core.CourseSession
import com.gzucm.pocket.core.FitnessGrade
import com.gzucm.pocket.core.LevelGrade
import com.gzucm.pocket.core.ClassReminder
import com.gzucm.pocket.core.Net
import com.gzucm.pocket.core.NetError
import com.gzucm.pocket.core.PowerDaily
import com.gzucm.pocket.core.PowerInfo
import com.gzucm.pocket.core.Semester
import com.gzucm.pocket.data.Ecard
import com.gzucm.pocket.data.Jw
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

enum class Phase { Loading, NeedLogin, Ready }

data class UiState(
    val phase: Phase = Phase.Loading,
    val semester: Semester? = null,
    val sessions: List<CourseSession> = emptyList(),
    val power: PowerInfo? = null,
    val powerDaily: List<PowerDaily> = emptyList(),
    val grades: List<CourseGrade> = emptyList(),
    val levels: List<LevelGrade> = emptyList(),
    val fitness: List<FitnessGrade> = emptyList(),
    val syncing: Boolean = false,
    val gradesLoading: Boolean = false,
    /** 成绩分批抓取进度：已完成 / 总数（课程 → 等级 → 体测） */
    val gradesDone: Int = 0,
    val gradesTotal: Int = 0,
    /** 当前正在抓哪一批 */
    val gradesStage: String? = null,
    val powerLoading: Boolean = false,
    /** 电费读取失败的具体原因，为空表示没出错 */
    val powerError: String? = null,
    val message: String? = null,
    val lastSync: Long = 0L,
    /** 逐周抓取进度：已完成 / 总周数。用于首屏显示「第 N/20 周」 */
    val progressDone: Int = 0,
    val progressTotal: Int = 0,
    /** 已经拿到数据的周次；不在里面的周显示「正在加载」，而不是空白 */
    val loadedWeeks: Set<Int> = emptySet(),
)

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
    private fun checkUpdateSilently() {
        val today = LocalDate.now().toString()
        if (store.lastUpdateCheckDate == today) return
        viewModelScope.launch {
            when (val r = com.gzucm.pocket.core.Updater.check()) {
                is com.gzucm.pocket.core.Updater.Result.Newer -> {
                    store.latestVersion = r.info.latestVersion
                    store.lastUpdateCheckDate = today
                    hasUpdate = true
                }
                com.gzucm.pocket.core.Updater.Result.UpToDate -> {
                    store.latestVersion = null
                    store.lastUpdateCheckDate = today
                    hasUpdate = false
                }
                is com.gzucm.pocket.core.Updater.Result.Failed -> Unit
            }
        }
    }

    /** 关于页手动查过之后同步一下红点状态 */
    fun refreshUpdateBadge() {
        hasUpdate = store.latestVersion?.let {
            com.gzucm.pocket.core.Updater.isNewer(it, com.gzucm.pocket.BuildConfig.VERSION_NAME)
        } ?: false
    }

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
        val sem = state.semester
        val ses = state.sessions
        if (sem != null && ses.isNotEmpty()) {
            runCatching {
                ClassReminder.scheduleAll(
                    getApplication(), sem, ses, store.classReminderEnabled,
                    classLeadFree, classLeadBreak,
                )
            }
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
        val sem = state.semester
        val ses = state.sessions
        if (sem != null && ses.isNotEmpty()) {
            runCatching {
                ClassReminder.scheduleAll(
                    getApplication(), sem, ses, enabled, classLeadFree, classLeadBreak,
                )
            }
        }
    }

    private val store = Graph.store

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
                progressTotal = cachedSemester.totalWeeks,
            )
        }
        viewModelScope.launch {
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
            checkUpdateSilently()
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
        if (state.phase != Phase.Ready) {
            state = state.copy(phase = Phase.Ready, message = null)
        }
        // 已经在同步就别重复开一份
        if (!state.syncing && state.sessions.isEmpty()) syncQuietly()
    }

    fun onLoggedIn(jwCookies: Map<String, String>, ecardCookies: Map<String, String>) {
        if (jwCookies.isNotEmpty()) Net.cookies.seed("jw.gzucm.edu.cn", jwCookies)
        if (ecardCookies.isNotEmpty()) Net.cookies.seed("ecard.gzucm.edu.cn", ecardCookies)
        state = state.copy(phase = Phase.Ready, message = null)
        loginExpired = false
        // 课表可能已经在 onJwReady 时抓过了，别重复
        if (state.sessions.isEmpty() && !state.syncing) syncQuietly()
        // 电费：登录后打个待刷新标记，用户进电费页时弹窗同步一次
        if (ecardCookies.isNotEmpty()) powerDirty = true
    }

    fun logout() {
        Net.cookies.clear()
        store.cachedSessions = null
        store.cachedSemester = null
        store.cachedPower = null
        state = UiState(phase = Phase.NeedLogin)
    }

    /** 从设置页点「重新登录」：清会话但保留缓存课程表，回到登录页 */
    fun reopenLogin() {
        state = state.copy(phase = Phase.NeedLogin)
    }

    // ── 同步 ────────────────────────────────────────────────────────────

    fun sync() {
        // 手动同步 = 课表 + 电费一起刷；电费要等用户进电费页才能取（会话在 WebView 里），
        // 所以这里只打标记，不做无用的后台请求
        powerDirty = true
        if (state.syncing) return
        viewModelScope.launch { doSync() }
    }

    private fun syncQuietly() {
        viewModelScope.launch { doSync() }
    }

    private suspend fun doSync() {
        state = state.copy(syncing = true, message = null)

        // ── 第一阶段：学期周历 + 当前周 ──────────────────────────────
        // 只等 2 次往返就能出画面。全量 20 周在好网络下也才 0.2 秒，
        // 但弱网时每次可能 1 秒以上，先给当前周能把白屏从几十秒压到几秒。
        val first = withContext(Dispatchers.IO) {
            runCatching {
                val code = store.semesterCode ?: Jw.guessSemesterCode()
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
            val showIt = if (!authIssue) true else store.loginExpiredNotifiedDate != today
            if (authIssue && showIt) store.loginExpiredNotifiedDate = today

            val sem = state.semester
            state = state.copy(
                syncing = false,
                message = if (showIt) describe(e) else state.message,
                phase = if (state.sessions.isEmpty()) Phase.NeedLogin else Phase.Ready,
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
                progressTotal = sem.totalWeeks,
                syncing = true,
            )
        }

        // ── 第二阶段：补齐其余周，边到边更新进度 ────────────────────
        val week = first.getOrThrow().second
        val restSessions = withContext(Dispatchers.IO) {
            runCatching {
                Jw.fetchWeeksExcept(semester, week) { done ->
                    state = state.copy(progressDone = done + 1)
                }
            }.getOrDefault(emptyList())
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
            // 课表变了就重排提醒（只排未来 3 天，代价很小）
            runCatching {
                ClassReminder.scheduleAll(
                    getApplication(), semester, sessions, store.classReminderEnabled,
                    classLeadFree, classLeadBreak,
                )
            }
            state = state.copy(
                phase = Phase.Ready,
                semester = semester,
                sessions = sessions,
                loadedWeeks = (1..semester.totalWeeks).toSet(),
                syncing = false,
                lastSync = store.lastSyncAt,
                message = null,
            )
        } else {
            state = state.copy(
                syncing = false,
                message = if (state.sessions.isEmpty()) "没能拿到课表数据，稍后可以下拉重试" else null,
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
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val grades = Jw.fetchCourseGrades()
                    state = state.copy(gradesDone = 1, gradesStage = "等级成绩")
                    val levels = runCatching { Jw.fetchLevelGrades() }.getOrDefault(emptyList())
                    state = state.copy(gradesDone = 2, gradesStage = "体测成绩")
                    val fitness = runCatching { Jw.fetchFitness() }.getOrDefault(emptyList())
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
                    gradesDone = 3, gradesStage = null,
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
        // 明细偶尔会取到空（页面还在初始化 / 分页没跑完）。
        // 空的时候保留原有明细，别把缓存里的历史数据清掉。
        val finalDaily = if (daily.isEmpty()) state.powerDaily else daily
        powerDirty = false
        state = state.copy(power = info, powerDaily = finalDaily, powerLoading = false, powerError = null)
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
