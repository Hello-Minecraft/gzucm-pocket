package com.gzucm.pocket.ui.workbench

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.gzucm.pocket.core.CampusApp
import com.gzucm.pocket.core.WEWORK_PACKAGE
import com.gzucm.pocket.core.WEWORK_WORKBENCH_SCHEME

/**
 * 把「只能企业微信打开」的卡片送到企业微信去。
 *
 * ## 为什么只能落到工作台
 *
 * 试过四条路，全都不通（2026-11-21 小米 14 实测，详见 [WEWORK_WORKBENCH_SCHEME] 的注释）：
 * `openurl?url=`、`openurl?redirecturl=`、`jump?target=open_h5` 都被静默拒绝，
 * `ACTION_VIEW` + https 更是直接 `Activity not started` —— 企业微信根本没注册
 * http 的接收器。唯一能用的是 `jump?target=workbench`。
 *
 * 所以点「智慧宿管」的体验是：跳过去 → 落在工作台 → 用户点那一格。
 * Toast 必须说清楚这件事，否则用户会以为跳错了。
 *
 * @return false 表示没装企业微信（或系统拒绝启动），调用方给个提示
 */
fun launchWeWorkWorkbench(ctx: Context): Boolean {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(WEWORK_WORKBENCH_SCHEME))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        /*
         * 必须 setPackage。
         *
         * 不指定的话，任何注册了 wxwork scheme 的 App 都能接走这一跳 ——
         * 这类 scheme 劫持在国产 ROM 上不是新鲜事，跳到一个不相干的 App
         * 比跳不过去更难受。
         */
        .setPackage(WEWORK_PACKAGE)
    return runCatching { ctx.startActivity(intent) }.isSuccess
}

/**
 * 用系统浏览器打开。
 *
 * 优先 **Chrome**：国产 ROM 的默认浏览器（尤其 OPPO 自带那个）会把任何链接
 * 先垫一段开屏广告 —— 2026-11-20 实测连腾讯问卷都被劫成周大福摇一摇。
 * 跳过去等于把用户卖给广告主。Chrome 没这毛病，而且它就是那个
 * 「实验室平台能正常打开」的环境。
 *
 * Chrome 不在（或者被禁用）就退回系统默认浏览器，再不行给个提示。
 */
fun openInBrowser(ctx: Context, url: String) {
    if (url.isBlank()) return
    val base = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val chrome = Intent(base).setPackage("com.android.chrome")
    val ok = runCatching { ctx.startActivity(chrome) }.isSuccess ||
        runCatching { ctx.startActivity(base) }.isSuccess
    if (!ok) {
        Toast.makeText(ctx, "这台手机没有可用的浏览器", Toast.LENGTH_LONG).show()
    }
}

/**
 * 处理「不在 App 内打开」的两类：[CampusAppKind.WEWORK] 和 [CampusAppKind.BROKEN]。
 *
 * 名字里带 Outside 是为了跟 RootScreen 里的 `openCampusApp` 状态区分开 ——
 * 那个是「要用内嵌 WebView 打开的应用」，两类不能混。
 */
fun openOutsideApp(ctx: Context, app: CampusApp) {
    when (app.kind) {
        com.gzucm.pocket.core.CampusAppKind.WEB -> Unit // 交给上层用内嵌 WebView 打开

        com.gzucm.pocket.core.CampusAppKind.WEWORK -> {
            val ok = launchWeWorkWorkbench(ctx)
            Toast.makeText(
                ctx,
                if (ok) "已打开企业微信，点「${app.name}」就行"
                else "这个应用只能用企业微信打开，但这台手机没装企业微信",
                Toast.LENGTH_LONG,
            ).show()
        }

        com.gzucm.pocket.core.CampusAppKind.BROKEN -> {
            Toast.makeText(
                ctx,
                app.note.ifBlank { "这个系统暂时打不开" },
                Toast.LENGTH_LONG,
            ).show()
        }
    }
}
