package com.gzucm.pocket.ui.power

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import com.gzucm.pocket.ui.adaptive.AdaptiveSheet
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.gzucm.pocket.core.PowerDaily
import com.gzucm.pocket.core.PowerInfo
import com.gzucm.pocket.data.Ecard

/**
 * 校园卡同步（WebView 通道）。
 *
 * 为什么要用 WebView 而不是 OkHttp：
 * 一卡通前面挂了 WAF（`SFP_Verify_Cookie`），服务端每个请求都可能换 JSESSIONID，
 * 而且会话在 `Path=/user` 下。把 cookie 从 WebView 搬进自建 CookieJar 再发请求，
 * 极易搬错（少一个 cookie、path 归错、UA 对不上都会被弹回登录页）。
 *
 * 这里让**页面自己发请求**：`fetch` 在页面上下文里执行，
 * cookie / referer / UA / WAF 校验全部由浏览器原生处理，不存在搬运问题。
 * 数据通过 `addJavascriptInterface` 回传给 Kotlin 解析。
 */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun EcardSyncSheet(
    /**
     * 显隐。由调用方传真实状态、**外层不要再包 if**。
     *
     * 包 if 的话组件第一次进组合时 visible 就已经是 true，
     * AnimatedVisibility 认为「初始就可见」，进入动画不播、
     * 退场时整棵子树被直接摘掉 —— 面板会「啪」地出现，
     * 平板侧栏完全没有滑动效果。
     */
    visible: Boolean = true,
    onDone: (balance: PowerInfo?, daily: List<PowerDaily>, error: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    /*
     * 所有抓取状态都必须 `remember(visible)`，不能只写 `remember {}`。
     *
     * 踩过的坑（致辉 2026-11-01 报的「第一次能读到、第二次报错」）：
     *   · 面板关掉时，AdaptiveSheet 里的 AnimatedVisibility 会销毁 WebView
     *   · 但本组件的 remember 状态**不会跟着重置** —— 它在这一层，不在里面
     *   · 于是第二次打开时 done 还是 true，`tryFinish()` 第一句
     *     `if (done) return` 把所有新数据全丢掉了
     *
     * 用 `remember(visible)`：visible 一变（关→开）就重新初始化，
     * 等于每次打开面板都是干净的一轮。
     *
     * 注意不能在 `LaunchedEffect(visible)` 里手动赋值重置 —— 那样先渲染
     * 一帧旧状态，界面会闪一下旧的余额/错误文案。
     */
    var status by remember(visible) { mutableStateOf("正在打开校园卡…") }
    var webRef by remember(visible) { mutableStateOf<WebView?>(null) }
    // 收齐两个响应后统一解析
    var balanceRaw by remember(visible) { mutableStateOf<String?>(null) }
    var pageHtml by remember(visible) { mutableStateOf("") }
    var dailyRaw by remember(visible) { mutableStateOf<String?>(null) }
    var dailyJsonRaw by remember(visible) { mutableStateOf<String?>(null) }
    var errorMsg by remember(visible) { mutableStateOf<String?>(null) }
    var done by remember(visible) { mutableStateOf(false) }

    fun tryFinish() {
        if (done) return
        val r = EcardSyncResult.tryBuild(
            balanceRaw = balanceRaw,
            dailyJsonRaw = dailyJsonRaw,
            dailyRaw = dailyRaw,
            pageHtml = pageHtml,
        ) ?: return  // 还没收齐，继续等
        done = true
        when (r) {
            is EcardSyncResult.Ok -> onDone(r.info, r.daily, null)
            is EcardSyncResult.Bad -> onDone(null, emptyList(), r.message)
            // RoomOnly 理论上走不到这里：房间单独回报是给 fail 兜底用的
            is EcardSyncResult.RoomOnly -> onDone(r.info, emptyList(), null)
        }
    }

    /**
     * 收尾：余额没拿到也必须回调。
     *
     * 之前的死结：`tryFinish()` 要求 balanceRaw 非空才往下走，
     * 而取余额失败时 balanceRaw 永远是 null → **onDone 永远不调用** →
     * 上层收不到任何结果，房间号也停在换绑前的旧值（2026-11-01 致辉遇到的）。
     *
     * 现在失败路径也会走这里：
     *   · 有房间信息就包一个 balance=-1 的 PowerInfo 带出去，
     *     上层只更新房间、不覆盖余额（见 AppViewModel.applyEcardSync）
     *   · 顺带把错误文案交给上层显示
     */
    fun finishWithError(msg: String) {
        if (done) return
        done = true
        val roomInfo = balanceRaw?.let { Ecard.parseRoomOnly(it, pageHtml) }
        onDone(roomInfo, emptyList(), msg)
    }

    AdaptiveSheet(
        visible = visible,
        onDismiss = onDismiss,
        paneTitle = "同步电费",
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .height(560.dp)
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "同步校园卡",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        status,
                        fontSize = 11.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                )
            }

            if (errorMsg != null) {
                Spacer(Modifier.height(10.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.error.copy(alpha = 0.12f))
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                ) {
                    Text(
                        errorMsg!!,
                        fontSize = 11.5.sp,
                        lineHeight = 17.sp,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.background),
            ) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.databaseEnabled = true
                            settings.userAgentString = settings.userAgentString

                            addJavascriptInterface(
                                object {
                                    @JavascriptInterface
                                    fun balance(json: String, html: String) {
                                        balanceRaw = json
                                        pageHtml = html
                                        status = "已取到余额"
                                        tryFinish()
                                    }

                                    @JavascriptInterface
                                    fun daily(html: String) {
                                        dailyRaw = html
                                        status = "已取到用电明细"
                                        tryFinish()
                                    }

                                    /** 分页接口返回 JSON，优先用它 */
                                    @JavascriptInterface
                                    fun dailyJson(json: String) {
                                        dailyJsonRaw = json
                                        status = "已取到用电明细"
                                        tryFinish()
                                    }

                                    @JavascriptInterface
                                    fun fail(msg: String) {
                                        android.util.Log.w("EcardSync", "fail: $msg")
                                        errorMsg = msg
                                        status = "同步失败"
                                        /*
                                         * 必须在这里收尾。
                                         *
                                         * 之前只设 errorMsg 不回调，上层永远等不到结果；
                                         * 更糟的是 tryFinish 要求 balanceRaw 非空，
                                         * 而失败时它一直是 null —— 整个流程就卡死在这儿。
                                         */
                                        finishWithError(msg)
                                    }

                                    /** 调试用：把中间状态打到 logcat，方便真机排查 */
                                    @JavascriptInterface
                                    fun log(msg: String) {
                                        android.util.Log.i("EcardSync", msg)
                                    }
                                },
                                "GzBridge",
                            )

                            webViewClient = object : WebViewClient() {
                                override fun onPageStarted(
                                    v: WebView?,
                                    url: String?,
                                    favicon: Bitmap?,
                                ) {
                                    status = "加载中…"
                                    // 钩子必须在页面自己的 JS 跑之前装好，
                                    // 否则页面启动就发出的 getBalance 会漏掉。
                                    if (url != null && url.contains("ecard.gzucm.edu.cn")) {
                                        v?.evaluateJavascript(HOOK_JS, null)
                                    }
                                }

                                override fun onPageFinished(v: WebView?, url: String?) {
                                    if (url == null) return
                                    // 还停在认证页：告诉用户要手动登录
                                    if (url.contains("authserver") || url.contains("/sso/login")) {
                                        status = "请在下方页面完成登录"
                                        return
                                    }
                                    // 关键：SSO 回跳后落点常常是 /user/index（个人资料页），
                                    // 不是电费页。之前只认 /user/powerfee/ 就永远等不到。
                                    // 现在只要进了一卡通域就主动导航到电费页。
                                    if (!url.contains("ecard.gzucm.edu.cn")) return

                                    if (url.contains("/user/powerfee/")) {
                                        status = "页面就绪，正在取数…"
                                        v?.evaluateJavascript(EXTRACT_JS, null)
                                    } else {
                                        status = "登录成功，正在打开电费页…"
                                        v?.loadUrl("${Ecard.BASE}/user/powerfee/index")
                                    }
                                }
                            }
                            loadUrl("${Ecard.BASE}/user/powerfee/index")
                            webRef = this
                        }
                    },
                    update = { webRef = it },
                )
            }

            Spacer(Modifier.height(8.dp))
            Text(
                "这一步在你手机的浏览器内核里完成，账号密码不经过 App",
                fontSize = 10.sp,
                lineHeight = 15.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
        }
    }
}

/**
 * 电费抓取的结果。
 *
 * 抽成纯数据 + 纯函数，是为了能单测「收齐 → 出结果」这段判定 ——
 * 这段出过两个 bug（`done` 残留、失败不回调），都很难在真机上重现。
 */
internal sealed interface EcardSyncResult {
    data class Ok(val info: com.gzucm.pocket.core.PowerInfo, val daily: List<com.gzucm.pocket.core.PowerDaily>) : EcardSyncResult
    data class RoomOnly(val info: com.gzucm.pocket.core.PowerInfo) : EcardSyncResult
    data class Bad(val message: String) : EcardSyncResult

    companion object {
        /**
         * 攒够数据就产出结果，不够返回 null（调用方继续等）。
         *
         * 规则：
         *   · 余额和明细**都到齐**才算 Ok
         *   · 余额到了但解析不出 → Bad（别静默吞掉，上层要显示原因）
         *   · 缺一个 → null
         */
        fun tryBuild(
            balanceRaw: String?,
            dailyJsonRaw: String?,
            dailyRaw: String?,
            pageHtml: String,
        ): EcardSyncResult? {
            val b = balanceRaw ?: return null
            if (dailyJsonRaw == null && dailyRaw == null) return null

            val info = com.gzucm.pocket.data.Ecard.parseBalance(b, pageHtml)
                ?: return Bad("余额接口返回异常：${b.take(150)}")

            val daily = when {
                !dailyJsonRaw.isNullOrBlank() -> com.gzucm.pocket.data.Ecard.parseDailyJson(dailyJsonRaw)
                !dailyRaw.isNullOrBlank() -> com.gzucm.pocket.data.Ecard.parseDaily(dailyRaw)
                else -> emptyList()
            }
            return Ok(info, daily)
        }
    }
}

/**
 * 页面脚本**运行之前**注入的钩子。
 *
 * 思路：不去猜参数、不去拼 URL，而是**监听页面自己发出的 AJAX**。
 * jQuery 走 XMLHttpRequest，所以劫持 `XMLHttpRequest.prototype.open/send`
 * 就能拿到页面自己对 `/user/powerfee/getBalance` 的请求 ——
 * 参数、cookie、token 全是页面自己算的，不可能错。
 *
 * 响应统一存进 `window.__gz`，等页面加载完再读。
 */
internal const val HOOK_JS = """
(function() {
  if (window.__gzInstalled) return;
  window.__gzInstalled = true;
  window.__gz = {};

  function save(url, text) {
    try {
      if (!url || !text) return;
      if (url.indexOf('getBalance') >= 0) window.__gz.balance = text;
      if (url.indexOf('getDailyDetails') >= 0) window.__gz.daily = text;
      if (url.indexOf('showDailyDetails') >= 0) window.__gz.show = text;
    } catch (e) {}
  }

  // ── 劫持 XMLHttpRequest（jQuery 的底层）──
  var XO = XMLHttpRequest.prototype.open;
  var XS = XMLHttpRequest.prototype.send;
  XMLHttpRequest.prototype.open = function(m, u) {
    this.__gzUrl = u;
    return XO.apply(this, arguments);
  };
  XMLHttpRequest.prototype.send = function() {
    var self = this;
    this.addEventListener('load', function() {
      try { save(self.__gzUrl, self.responseText); } catch (e) {}
    });
    return XS.apply(this, arguments);
  };

  // ── 劫持 fetch（页面里有部分逻辑用 fetch）──
  var OF = window.fetch;
  if (OF) {
    window.fetch = function(input, init) {
      var u = (typeof input === 'string') ? input : (input && input.url) || '';
      return OF.apply(this, arguments).then(function(resp) {
        try {
          resp.clone().text().then(function(t){ save(u, t); });
        } catch (e) {}
        return resp;
      });
    };
  }
})();
"""

/**
 * 页面加载完成后执行：优先读钩子抓到的原始响应，
 * 抓不到再自己补一次请求。
 */
internal const val EXTRACT_JS = """
(function() {
  function val(name) {
    var list = document.querySelectorAll('input[name="' + name + '"]');
    for (var i = 0; i < list.length; i++) {
      if (list[i].value) return list[i].value;
    }
    return '';
  }
  function powerTipsText() {
    var list = document.querySelectorAll('#powerTips');
    for (var i = 0; i < list.length; i++) {
      var t = (list[i].textContent || '').replace(/\s+/g, ' ').trim();
      if (t) return t;
    }
    return '';
  }

  var area = val('schoolAreaNo');
  var bld = val('buildingNo');
  var room = val('roomNum');
  var roomTxt = val('roomChooseText');
  var implType = val('implType') || 'CGCOMMON1001';
  var html = document.documentElement.outerHTML;
  var token = (html.match(/var token\s*=\s*['"]([A-Za-z0-9]+)['"]/) || [])[1] || '';

  GzBridge.log('INIT url=' + location.href);
  GzBridge.log('INIT area=' + area + ' bld=' + bld + ' room=' + room +
               ' impl=' + implType + ' token=' + token);
  GzBridge.log('HOOK balance=' + (window.__gz && window.__gz.balance ? 'yes' : 'no') +
               ' daily=' + (window.__gz && window.__gz.daily ? 'yes' : 'no'));

  var d = new Date();
  var ym = d.getFullYear() + '-' + ('0' + (d.getMonth() + 1)).slice(-2);
  var PAGE_SIZE = 30;

  /**
   * 只回报房间信息，不带余额。
   *
   * 为什么需要：用户在小程序里换绑房间后，房间变了但余额可能读不到
   * （页面改版、接口变了）。如果只在「余额也拿到了」时才更新，
   * 卡片会一直显示**换绑前的旧房间号** —— 用户会以为 App 坏了。
   *
   * 这里单独回一条，让 Kotlin 侧只改 roomLabel，不碰余额。
   */
  function emitRoomOnly() {
    if (!room) return;
    var payload = JSON.stringify({
      ret: true, msg: 'room-only',
      obj: {
        roomNum: room, schoolAreaNo: area, buildingNo: bld,
        roomLabel: roomTxt, token: token, roomOnly: true
      }
    });
    GzBridge.log('EMIT roomOnly label=' + roomTxt + ' room=' + room);
    GzBridge.balance(payload, html);
  }

  /**
   * 从 getBalance 的 JSON 里取房间标签。
   *
   * **这是最可靠的来源**（2026-11-01 实测）。接口返回的是当前真正绑定的房间：
   *   {"schoolArea":"广州中医药大学","building":"三元里6栋","room":"213"}
   *
   * 为什么不继续用 `#roomChooseText`：那个 input 的 value 在换绑后
   * 可能还是旧值（致辉换绑成 211 后它仍写着 213），
   * 拿它当房间名会一直显示错的。
   */
  function roomLabelFromJson(text) {
    try {
      var j = JSON.parse(text);
      if (!j || !j.obj) return '';
      var o = j.obj;
      if (!o.building || !o.room) return '';
      // 「三元里6栋」+「213」→「三元里6栋   213」，和学校页面写法一致
      return String(o.building).replace(/\s+/g, '') + '   ' + String(o.room);
    } catch (e) { return ''; }
  }

  function emit(balanceNum, source, rawTips, rawJson) {
    // 接口给的是「3.23」但也可能是「3.23度」/「剩余电量：3.23」，
    // 先把所有非数字字符剥掉，否则 Kotlin 的 toDoubleOrNull 会失败变成 0。
    var clean = String(balanceNum == null ? '' : balanceNum).replace(/[^\d.]/g, '');
    if (clean === '' || isNaN(parseFloat(clean))) {
      // 接口值不可用，退回页面文字里最后一个数字
      var m = String(rawTips || '').match(/([\d.]+)/);
      clean = m ? m[1] : '';
    }
    // 一个数字都没拿到：不要发「0 度」，那会把已有数据覆盖成 0
    if (clean === '' || isNaN(parseFloat(clean))) {
      GzBridge.fail('没拿到余额（可能未登录），保持原数据不动');
      return;
    }
    GzBridge.log('EMIT balance=' + clean + ' source=' + source);
    /*
     * 房间标签的优先级：
     *   ① getBalance 返回的 building + room —— 当前真正绑定的
     *   ② 页面 #roomChooseText —— 可能是换绑前的旧值
     *   ③ 什么都没有就不填，让 Kotlin 侧保留原值，别写成空
     */
    var label = roomLabelFromJson(rawJson) || roomTxt || '';
    GzBridge.log('EMIT label=' + (label || '(空)'));
    var payload = JSON.stringify({
      ret: true, msg: 'ok',
      obj: {
        roomNum: room, schoolAreaNo: area, buildingNo: bld,
        powerBalance: clean, du: '度',
        roomLabel: label, token: token, rawTips: rawTips || '', source: source
      }
    });
    GzBridge.balance(payload, html);
    fetchMonth(1, [], function(all){
      GzBridge.log('EMIT days=' + all.length);
      GzBridge.dailyJson(JSON.stringify({
        ret: true, msg: 'ok',
        obj: { dailyDetailsInfos: all, totalPage: 1 }
      }));
    });
  }

  // 从 JSON 里挖余额：formatPowerBalanceStr 优先，其次 powerBalance
  function numFromJson(text) {
    if (!text) return null;
    var j = null;
    try { j = JSON.parse(text); } catch (e) { return null; }
    if (!j || !j.ret || !j.obj) return null;
    var v = j.obj.formatPowerBalanceStr;
    if (v === undefined || v === null || v === '') v = j.obj.powerBalance;
    if (v === undefined || v === null || v === '') return null;
    return String(v);
  }

  function fetchMonth(pageNum, acc, done) {
    if (pageNum > 12) { done(acc); return; }
    var body = 'roomNum=' + encodeURIComponent(room) +
               '&lastDate=' + encodeURIComponent(ym) +
               '&type=2&implType=' + encodeURIComponent(implType) +
               '&schoolAreaNo=' + encodeURIComponent(area) +
               '&pageNum=' + pageNum +
               '&pageSize=' + PAGE_SIZE +
               '&token=' + encodeURIComponent(token) +
               '&from=';
    fetch('/user/powerfee/getDailyDetails', {
      method: 'POST', credentials: 'same-origin',
      headers: {
        'Content-Type': 'application/x-www-form-urlencoded; charset=UTF-8',
        'X-Requested-With': 'XMLHttpRequest'
      },
      body: body
    }).then(function(r){ return r.text(); })
      .then(function(t){
        var j = null;
        try { j = JSON.parse(t); } catch (e) {}
        if (!j || !j.ret || !j.obj || !j.obj.dailyDetailsInfos) {
          GzBridge.log('DAY p' + pageNum + ' bad=' + t.substring(0, 200));
          done(acc); return;
        }
        var arr = j.obj.dailyDetailsInfos;
        GzBridge.log('DAY p' + pageNum + ' n=' + arr.length);
        for (var i = 0; i < arr.length; i++) acc.push(arr[i]);
        if (arr.length < PAGE_SIZE) { done(acc); return; }
        fetchMonth(pageNum + 1, acc, done);
      })
      .catch(function(e){ GzBridge.log('DAY err=' + e); done(acc); });
  }

  // 等页面自己的 AJAX 跑完（最多 4 秒）
  var waited = 0;
  var triedClick = false;
  var timer = setInterval(function(){
    waited += 400;
    var got = window.__gz && window.__gz.balance;
    var tips = powerTipsText();
    if (got || tips || waited >= 4000) {
      clearInterval(timer);
      var v = numFromJson(got);
      if (v !== null) { emit(v, 'hook', tips, got); return; }

      /*
       * 钩子没抓到。
       *
       * 先**让页面自己发请求** —— 点它自己的「查询」按钮，
       * 或者调它自己的刷新函数。这样参数、token 全是页面算的。
       *
       * 为什么不再自己拼参数请求 getBalance：
       *   那个接口是「查任意房间」用的，自己的房间走的是
       *   `getSelfBalance` 之类带会话的路径，参数签名不一样，
       *   照着页面字段拼出来必然返回 {"ret":false,"msg":"查询失败"}。
       *   之前之所以能成，是因为脚本碰巧在页面刚加载时跑，
       *   钩子抓到了页面自己发的请求。
       */
      if (!triedClick) {
        triedClick = true;
        try {
          // 常见写法：查询按钮 id / class / onclick 里带 query|balance|search
          var btn = document.querySelector(
            '#queryBtn, #searchBtn, .query-balance, [onclick*="getBalance"], [onclick*="queryPower"]'
          );
          if (btn) { GzBridge.log('CLICK btn=' + (btn.id || btn.className)); btn.click(); }
          // 有些版本把逻辑挂在全局函数上
          if (typeof window.getBalance === 'function') {
            GzBridge.log('CALL window.getBalance()');
            window.getBalance();
          } else if (typeof window.queryPower === 'function') {
            GzBridge.log('CALL window.queryPower()');
            window.queryPower();
          }
        } catch (e) { GzBridge.log('CLICK err=' + e); }
        // 给页面 1.2 秒反应，再判断一次
        setTimeout(function(){
          var got2 = window.__gz && window.__gz.balance;
          var v2 = numFromJson(got2);
          var tips2 = powerTipsText();
          if (v2 !== null) { emit(v2, 'hook-after-click', tips2, got2); return; }
          var m2 = (tips2.match(/([\d.]+)/) || [])[1];
          if (m2 !== undefined) { emit(m2, 'dom', tips2, got2 || ''); return; }
          diagnose(tips2);
        }, 1200);
        return;
      }
      diagnose(tips);
    }
  }, 400);

  /**
   * 一条都拿不到时，把「页面上到底有什么」报回去。
   *
   * 之前只回一句「余额为空」，用户和我都不知道是没登录、
   * 换了新版页面、还是余额藏在别的元素里。现在把结构线索带上，
   * 下次一看就知道该改哪个选择器。
   */
  function diagnose(tips) {
    var clues = [];
    clues.push('url=' + location.href);
    clues.push('powerTips=' + (tips || '(无)'));
    clues.push('area=' + area + ' bld=' + bld + ' room=' + room);
    clues.push('token=' + (token ? 'yes' : 'no'));

    // 页面上所有含「剩余/电量/余额」的可见文字，最多 3 条
    var hits = [];
    var all = document.querySelectorAll('body *');
    for (var i = 0; i < all.length && hits.length < 3; i++) {
      var el = all[i];
      if (el.children.length > 0) continue;
      var t = (el.textContent || '').replace(/\s+/g, ' ').trim();
      if (t && t.length < 40 && /剩余|电量|余额/.test(t)) {
        hits.push((el.id ? '#' + el.id : el.className ? '.' + el.className : el.tagName) + ':' + t);
      }
    }
    clues.push('线索=' + (hits.length ? hits.join(' | ') : '(页面上没找到)'));
    // 先把房间号带回去（用户可能刚换绑，房间变了但余额读不到）
    emitRoomOnly();
    GzBridge.fail('余额没取到。' + clues.join('；'));
  }
})();
"""