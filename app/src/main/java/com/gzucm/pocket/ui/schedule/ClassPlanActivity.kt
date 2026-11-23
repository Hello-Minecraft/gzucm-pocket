package com.gzucm.pocket.ui.schedule

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gzucm.pocket.core.ClassTaskDto
import com.gzucm.pocket.ui.AppViewModel
import com.gzucm.pocket.ui.icons.AppIcons
import com.gzucm.pocket.ui.theme.GzucmTheme
import com.gzucm.pocket.ui.theme.ThemeState

/**
 * 班级日程 Activity：学委维护清单、同学认领导入，全在这一个独立窗口里。
 *
 * ## 为什么从底部面板搬出来
 *
 * 原来它塞在「我的日程」的 AdaptiveSheet 里（学委侧）和首页的一个居中弹窗里
 * （同学侧）。两个毛病都在手势上：
 *   · 编辑页有输入框和日期选择器，手指在屏幕下半部分活动，面板只要被往下带一下
 *     就整页关掉，填了一半的要求全没了；
 *   · 认领 / 详情要列几十行条目，往下翻和「下拉关闭」是同一个滑动方向。
 *
 * 独立 Activity 之后，往下划就是内容滚动，退出只有返回键和左上箭头两种明确动作。
 *
 * ## 两种模式
 *
 * [MODE_MANAGE] 学委侧：清单列表 → 编辑某一份 → 改条目 / 从日程导入。
 * [MODE_CLAIM] 同学侧：认领（选清单 + 输密钥）→ 清单详情（勾选导入）。
 *
 * 模式只在 `onCreate` 读一次；进来之后页面都在 [Page] 里切，不再开新窗口 ——
 * 少一层窗口就少一次「返回键到底关哪一层」的歧义。
 */
class ClassPlanActivity : ComponentActivity() {

    companion object {
        /** 学委侧：清单列表 / 编辑 / 条目编辑 / 从日程导入 */
        const val MODE_MANAGE = "manage"
        /** 同学侧：认领 / 详情 */
        const val MODE_CLAIM = "claim"

        private const val EXTRA_MODE = "mode"

        /** 统一从这里建 Intent，调用方不用关心参数名 */
        fun intent(ctx: Context, mode: String): Intent =
            Intent(ctx, ClassPlanActivity::class.java).putExtra(EXTRA_MODE, mode)
    }

    /**
     * 自己的 ViewModel 实例。
     *
     * 和 MainActivity 那个不是一个对象 —— 两个 Activity 各有一份内存 state，
     * 共同的只有 SharedPreferences（[com.gzucm.pocket.core.ClassPlanStore]）。
     * 所以这里保存的清单，主界面回到前台时要重新读一遍（见 AppViewModel.reloadClassPlans）。
     */
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 配色跟主界面保持一致，否则深色主题下进这页会闪一下浅色
        runCatching {
            val store = com.gzucm.pocket.core.SessionStore(applicationContext)
            ThemeState.init(store.themeSeed, store.darkMode)
        }

        // 只读一次：模式在页面生命周期里不会变
        val mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_CLAIM

        setContent {
            GzucmTheme(
                seed = ThemeState.seed,
                darkTheme = ThemeState.isDark(androidx.compose.foundation.isSystemInDarkTheme()),
            ) {
                Surface(Modifier.fillMaxSize()) {
                    ClassPlanScreen(
                        mode = mode,
                        vm = vm,
                        onFinish = { finish() },
                    )
                }
            }
        }
    }
}

/**
 * 班级日程内部的页面。
 *
 * 用状态切换而不是 Dialog / 面板：这一套页面有明确的「上一层」关系，
 * 系统返回键、左上箭头、页面里的「返回」按钮都要落到同一个动作上 ——
 * 只有把层级集中在一个状态里才做得到。
 */
internal sealed interface Page {

    /** 学委侧：我的清单列表 */
    data object Plans : Page

    /** 学委侧：某一份清单的编辑页 */
    data object Editor : Page

    /** 学委侧：编辑清单里的一条（[task] 非空 = 改；[copyFrom] 非空 = 复制） */
    data class TaskEdit(val task: ClassTaskDto?, val copyFrom: ClassTaskDto?) : Page

    /** 学委侧：从自己的日程里挑几条搬进清单 */
    data object Pick : Page

    /** 同学侧：选清单 + 输密钥 */
    data object Claim : Page

    /** 同学侧：清单详情 + 勾选导入（[id] = 云端清单 id） */
    data class Detail(val id: String) : Page
}

/**
 * 整页骨架：顶栏（返回箭头 + 标题）+ 内容区。
 *
 * 顶栏承担两件事：
 *   1. `statusBarsPadding()`：`enableEdgeToEdge()` 之后内容会画到状态栏底下，
 *      不补这一段标题就被时间/信号压住了；
 *   2. 一个**永远在同一个位置**的返回 —— 内容再长也不用滑回顶部才能退。
 *
 * 内容区留 `navigationBarsPadding()`：手势条 / 三键条压住底部按钮是这类
 * 全屏页最容易漏的一处（编辑页的「保存」正好在最下面）。
 */
@Composable
private fun ClassPlanScreen(
    mode: String,
    vm: AppViewModel,
    onFinish: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val ctx = LocalContext.current

    /*
     * 初始页。
     *
     * 同学侧**已认领**就直接进详情：他点的是首页那行的「查看」，不是「认领」；
     * 再让他输一遍密钥等于每次看清单都要找学委要一次口令。
     * 未认领 / 本地还没有索引 → 认领页。
     */
    var page by remember {
        mutableStateOf<Page>(
            if (mode == ClassPlanActivity.MODE_MANAGE) Page.Plans else initialClaimPage(vm),
        )
    }

    // 导入结果、发布结果这些一句话提示，都走系统 Toast（不占界面位置）
    LaunchedEffect(vm.classPlanToast) {
        val msg = vm.classPlanToast ?: return@LaunchedEffect
        Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
        vm.clearClassPlanToast()
    }

    /*
     * 详情页是不是「已经认领的那份」。
     *
     * 已认领 = 用户是从首页「查看」进来的，返回就该回主界面；
     * 未认领 = 从认领页刚输完密钥过来，返回回认领页（他可能还要改密钥）。
     * 认领状态只有 ViewModel 知道（订阅表），这里直接问它。
     */
    val detailClaimed = vm.classPlanDetail?.claimed == true

    /** 所有返回入口共用这一份层级，免得箭头和返回键各退一层 */
    val goBack: () -> Unit = {
        when (val p = page) {
            is Page.TaskEdit, Page.Pick -> page = Page.Editor
            Page.Editor -> page = Page.Plans
            // 已认领 = 从首页「查看」进来的，退回主界面；未认领 = 从认领页过来的
            is Page.Detail -> if (detailClaimed) onFinish() else page = Page.Claim
            else -> onFinish()
        }
    }

    BackHandler(onBack = goBack)

    Column(
        Modifier
            .fillMaxSize()
            .background(scheme.background),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(onClick = goBack),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    AppIcons.ArrowBack,
                    contentDescription = "返回",
                    tint = scheme.onSurface,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(6.dp))
            Text(
                // 每一层的标题不在页面里重复写，统一在这里出一行
                when (page) {
                    Page.Claim -> "认领班级日程"
                    Page.Pick -> "从我的日程导入"
                    else -> "班级日程"
                },
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = scheme.onSurface,
            )
        }

        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .navigationBarsPadding(),
        ) {
            when (val p = page) {
                // ── 学委侧 ──────────────────────────────────────────
                Page.Plans, Page.Editor, Page.Pick, is Page.TaskEdit -> ClassPlanManageContent(
                    page = p,
                    onPage = { page = it },
                    plan = vm.state.classPlan,
                    onPlanChange = { vm.saveClassPlan(it) },
                    myAgendas = vm.state.agenda,
                    // 导入是批量写：一次落盘、一次重排闹钟（见 replaceAgendas 的注释）
                    onImportToAgenda = { vm.replaceAgendas(it) },
                    maintainer = vm.state.studentCard?.name,
                    studentClass = vm.state.studentCard?.className,
                    onPublish = { plan ->
                        vm.publishClassPlan(plan) { msg ->
                            Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
                        }
                    },
                    // 手动兜底：本地索引可能是旧的，强制拉一次（内部还有 60 秒防连点）
                    onFetchFromClass = { vm.syncClassPlan(force = true) },
                    onDeletePublished = {
                        vm.deletePublishedClassPlan { msg ->
                            Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
                        }
                    },
                    plans = vm.state.classPlans,
                    onSelectPlan = { vm.editClassPlan(it) },
                    onCreatePlan = { name -> vm.newClassPlan(name) },
                    publishedPlanKeys = vm.state.publishedPlanKeys,
                )

                // ── 同学侧 ──────────────────────────────────────────
                Page.Claim -> {
                    val claimables =
                        (vm.classPlanHint?.bar as? ClassPlanBarState.Unclaimed)?.plans.orEmpty()
                    /*
                     * 本地还没有索引（第一次用 / 换了台手机）时补一次同步。
                     * 和原来弹窗的做法一致：没东西可选时，先去问一次服务端，
                     * 而不是让同学对着一个空列表发愣。
                     */
                    LaunchedEffect(claimables.isEmpty()) {
                        if (claimables.isEmpty()) vm.syncClassPlan(force = true)
                    }
                    ClassPlanClaimScreen(
                        className = vm.state.studentCard?.className.orEmpty(),
                        plans = claimables,
                        onSubmitKey = { planId, key, onResult ->
                            vm.submitClassPlanKey(planId, key) { ok ->
                                onResult(ok)
                                // 密钥对上了才进详情；不对就停在这一页改输入
                                if (ok) page = Page.Detail(planId)
                            }
                        },
                        onClose = onFinish,
                    )
                }

                is Page.Detail -> ClassPlanDetailPage(
                    vm = vm,
                    id = p.id,
                    onUnsubscribed = { page = Page.Claim },
                )
            }
        }
    }
}

/** 同学侧的入口页：已认领就直接看详情，否则先认领 */
private fun initialClaimPage(vm: AppViewModel): Page = when (val bar = vm.classPlanHint?.bar) {
    is ClassPlanBarState.Claimed -> Page.Detail(bar.id)
    is ClassPlanBarState.Updated -> Page.Detail(bar.id)
    else -> Page.Claim
}

/**
 * 详情页：内容在 ViewModel 里，这里只等它到货。
 *
 * 两个 VM 实例意味着打开详情必须重新拉一次 —— 不能指望主界面 VM 里
 * 那份 `classPlanDetail`（那是另一个对象的字段）。
 */
@Composable
private fun ClassPlanDetailPage(
    vm: AppViewModel,
    id: String,
    /** 取消认领后清单就不存在了，回认领页 */
    onUnsubscribed: () -> Unit,
) {
    LaunchedEffect(id) {
        if (vm.classPlanDetail?.id != id) vm.openClassPlanDetail(id)
    }

    val detail = vm.classPlanDetail
    if (detail == null || detail.id != id) {
        // 有缓存时几乎瞬间；没缓存就是一次网络往返，给一句话总比白屏好
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "正在读取清单…",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    ClassPlanDetailContent(
        detail = detail,
        onConfirm = { vm.confirmClassPlanImport(it) },
        onUnsubscribe = {
            vm.unsubscribeClassPlan(id)
            onUnsubscribed()
        },
    )
}
