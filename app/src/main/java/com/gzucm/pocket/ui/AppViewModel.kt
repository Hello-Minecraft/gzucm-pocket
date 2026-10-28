package com.gzucm.pocket.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gzucm.pocket.Graph
import com.gzucm.pocket.core.CourseGrade
import com.gzucm.pocket.core.CourseSession
import com.gzucm.pocket.core.FitnessGrade
import com.gzucm.pocket.core.LevelGrade
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
    val powerLoading: Boolean = false,
    /** 电费读取失败的具体原因，为空表示没出错 */
    val powerError: String? = null,
    val message: String? = null,
    val lastSync: Long = 0L,
)

class AppViewModel(app: Application) : AndroidViewModel(app) {

    var state by mutableStateOf(UiState())
        private set

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
                // 会话死了。有缓存就先进去看离线数据，同时把 phase 标成 NeedLogin
                // 让用户可以从设置里重新登录；没缓存就直接回登录页。
                state = state.copy(
                    phase = if (cachedSessions.isEmpty()) Phase.NeedLogin else Phase.Ready,
                    message = if (cachedSessions.isEmpty()) null else "登录状态已过期，当前显示离线缓存",
                )
                return@launch
            }
            state = state.copy(phase = Phase.Ready)
            syncQuietly()
            loadPowerQuietly()
        }
    }

    /** 登录成功后由 WebView 回调：注入两套 cookie 并开始同步 */
    fun onLoggedIn(jwCookies: Map<String, String>, ecardCookies: Map<String, String>) {
        if (jwCookies.isNotEmpty()) Net.cookies.seed("jw.gzucm.edu.cn", jwCookies)
        if (ecardCookies.isNotEmpty()) Net.cookies.seed("ecard.gzucm.edu.cn", ecardCookies)
        state = state.copy(phase = Phase.Ready, message = null)
        syncQuietly()
        loadPowerQuietly()
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
        if (state.syncing) return
        viewModelScope.launch { doSync() }
    }

    private fun syncQuietly() {
        viewModelScope.launch { doSync() }
    }

    private suspend fun doSync() {
        state = state.copy(syncing = true, message = null)
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val code = store.semesterCode ?: Jw.guessSemesterCode()
                var semester = Jw.fetchSemester(code)
                if (semester == null) {
                    val alt = Jw.previousSemesterCode(code)
                    semester = Jw.fetchSemester(alt)
                }
                semester ?: error("无法获取教学周历")
                // 逐周抓取：能拿到上课内容（sknrjj）与真实日期，总表没有这些
                val sessions = Jw.fetchDetailedSessions(semester)
                semester to sessions
            }
        }
        result.onSuccess { (semester, sessions) ->
            store.semesterCode = semester.code
            store.cachedSemester = Net.json.encodeToString(Semester.serializer(), semester)
            store.cachedSessions = Net.json.encodeToString(
                kotlinx.serialization.builtins.ListSerializer(CourseSession.serializer()),
                sessions,
            )
            store.lastSyncAt = System.currentTimeMillis()
            val week = semester.weekOf(LocalDate.now()).coerceIn(1, semester.totalWeeks)
            currentWeek = week
            viewingWeek = week
            state = state.copy(
                phase = Phase.Ready,
                semester = semester,
                sessions = sessions,
                syncing = false,
                lastSync = store.lastSyncAt,
                message = null,
            )
        }.onFailure { e ->
            state = state.copy(
                syncing = false,
                message = describe(e),
                phase = if (state.sessions.isEmpty()) Phase.NeedLogin else Phase.Ready,
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
        if (!force && state.grades.isNotEmpty()) return
        state = state.copy(gradesLoading = true, message = null)
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val grades = Jw.fetchCourseGrades()
                    val levels = runCatching { Jw.fetchLevelGrades() }.getOrDefault(emptyList())
                    val fitness = runCatching { Jw.fetchFitness() }.getOrDefault(emptyList())
                    Triple(grades, levels, fitness)
                }
            }
            r.onSuccess { (g, l, f) ->
                state = state.copy(grades = g, levels = l, fitness = f, gradesLoading = false)
            }.onFailure { e ->
                state = state.copy(gradesLoading = false, message = describe(e))
            }
        }
    }

    // ── 电费 ────────────────────────────────────────────────────────────

    fun loadPower(force: Boolean = false) {
        if (state.powerLoading) return
        if (!force && state.power != null) return
        state = state.copy(powerLoading = true)
        viewModelScope.launch { doLoadPower() }
    }

    private fun loadPowerQuietly() {
        if (state.power != null) return
        viewModelScope.launch { doLoadPower() }
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
            state = state.copy(power = info, powerDaily = daily, powerLoading = false, powerError = null)
            store.cachedPower = Net.json.encodeToString(PowerInfo.serializer(), info)
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
        is com.gzucm.pocket.core.NetError.Unauthorized -> "校园卡登录已失效，需要重新同步"
        is com.gzucm.pocket.core.NetError.Unreachable -> "网络不可达，检查一下网络连接"
        is com.gzucm.pocket.core.NetError.Http -> "服务器返回 ${e.message}"
        is com.gzucm.pocket.core.NetError.Parse -> e.message ?: "数据解析失败"
        else -> "读取失败：${e.javaClass.simpleName}"
    }

    /** WebView 通道拿到的数据，直接落库 */
    fun applyEcardSync(info: PowerInfo, daily: List<PowerDaily>) {
        state = state.copy(power = info, powerDaily = daily, powerLoading = false, powerError = null)
        store.cachedPower = Net.json.encodeToString(PowerInfo.serializer(), info)
        // 顺手算好「可用天数」存下来，每日巡检的 Worker 直接读，不用联网
        val recent = daily.sortedBy { it.date }.takeLast(7)
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

    private fun describe(e: Throwable): String = when (e) {
        is NetError.Unauthorized -> "登录状态已过期，请重新登录"
        is NetError.Unreachable -> "网络不可达，检查是否连着校园网"
        is NetError.Parse -> e.message ?: "数据解析失败"
        is NetError.Http -> "服务器返回 ${e.message}"
        else -> e.message ?: e.javaClass.simpleName
    }
}
