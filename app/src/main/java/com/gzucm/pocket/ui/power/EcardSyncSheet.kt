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
    onDone: (balance: PowerInfo?, daily: List<PowerDaily>, error: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var status by remember { mutableStateOf("正在打开校园卡…") }
    var webRef by remember { mutableStateOf<WebView?>(null) }
    // 收齐两个响应后统一解析
    var balanceRaw by remember { mutableStateOf<String?>(null) }
    var pageHtml by remember { mutableStateOf("") }
    var dailyRaw by remember { mutableStateOf<String?>(null) }
    var dailyJsonRaw by remember { mutableStateOf<String?>(null) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var done by remember { mutableStateOf(false) }

    fun tryFinish() {
        if (done) return
        val b = balanceRaw ?: return
        // 明细优先用分页 JSON，退回到 HTML
        val dJson = dailyJsonRaw
        val dHtml = dailyRaw
        if (dJson == null && dHtml == null) return
        done = true

        val info = Ecard.parseBalance(b, pageHtml)
        if (info == null) {
            onDone(null, emptyList(), "余额接口返回异常：${b.take(150)}")
            return
        }
        val daily = when {
            !dJson.isNullOrBlank() -> {
                Ecard.parseDailyJson(dJson)
            }
            !dHtml.isNullOrBlank() -> Ecard.parseDaily(dHtml)
            else -> emptyList()
        }
        onDone(info, daily, null)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
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

  function emit(balanceNum, source, rawTips) {
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
    var payload = JSON.stringify({
      ret: true, msg: 'ok',
      obj: {
        roomNum: room, schoolAreaNo: area, buildingNo: bld,
        powerBalance: clean, du: '度',
        roomLabel: roomTxt, token: token, rawTips: rawTips || '', source: source
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
  var timer = setInterval(function(){
    waited += 400;
    var got = window.__gz && window.__gz.balance;
    var tips = powerTipsText();
    if (got || tips || waited >= 4000) {
      clearInterval(timer);
      var v = numFromJson(got);
      if (v !== null) { emit(v, 'hook', tips); return; }
      // 钩子没抓到 → 自己补一次（参数照抄页面逻辑）
      var body = 'implType=' + encodeURIComponent(implType) +
                 '&schoolAreaNo=' + encodeURIComponent(area) +
                 '&buildingNo=' + encodeURIComponent(bld) +
                 '&roomNum=' + encodeURIComponent(room) +
                 '&from=&token=' + encodeURIComponent(token);
      GzBridge.log('SELF body=' + body);
      fetch('/user/powerfee/getBalance', {
        method: 'POST', credentials: 'same-origin',
        headers: {
          'Content-Type': 'application/x-www-form-urlencoded; charset=UTF-8',
          'X-Requested-With': 'XMLHttpRequest'
        },
        body: body
      }).then(function(r){ return r.text(); })
        .then(function(t){
          GzBridge.log('SELF resp=' + t.substring(0, 300));
          var n = numFromJson(t);
          if (n !== null) { emit(n, 'self', tips); return; }
          var m = (tips.match(/([\d.]+)/) || [])[1];
          if (m !== undefined) { emit(m, 'dom', tips); return; }
          GzBridge.fail('余额为空：页面无 #powerTips，接口也未返回余额');
        })
        .catch(function(e){
          GzBridge.log('SELF err=' + e);
          var m = (tips.match(/([\d.]+)/) || [])[1];
          if (m !== undefined) { emit(m, 'dom', tips); return; }
          GzBridge.fail('请求失败：' + e);
        });
    }
  }, 400);
})();
"""