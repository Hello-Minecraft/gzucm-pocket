"""DD订水 登录验证工具（本地）。

密码只在浏览器 → 本机 127.0.0.1 → ddds.shengzekeji.cn 之间流转，不落盘、不进对话。
登录成功后展示用户信息、订单统计与商品水价，用来确认接口可复刻。

用法：python dd_login.py   →  浏览器打开 http://127.0.0.1:8899
"""

import json
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

from flask import Flask, jsonify, request

BASE = "https://ddds.shengzekeji.cn"
ROOT = Path(__file__).resolve().parent
DATA = ROOT / "data"
DATA.mkdir(exist_ok=True)

app = Flask(__name__)
_opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
_state = {"token": None, "phone": None}


def _post(path: str, payload: dict) -> dict:
    req = urllib.request.Request(
        BASE + path,
        data=json.dumps(payload).encode(),
        headers={
            "Content-Type": "application/json",
            "Referer": "https://servicewechat.com/wx31534ce9348737da/94/page-frame.html",
            "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) MicroMessenger/7.0.20",
        },
        method="POST",
    )
    return json.loads(_opener.open(req, timeout=20).read().decode())


def _get(path: str) -> dict:
    req = urllib.request.Request(
        BASE + path,
        headers={
            "Authorization": "Bearer " + (_state["token"] or ""),
            "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) MicroMessenger/7.0.20",
            "xweb_xhr": "1",
        },
    )
    return json.loads(_opener.open(req, timeout=20).read().decode())


@app.post("/api/login")
def login():
    body = request.get_json(force=True, silent=True) or {}
    phone = str(body.get("phone", "")).strip()
    pwd = str(body.get("password", ""))
    if not phone or not pwd:
        return jsonify({"ok": False, "msg": "手机号和密码都要填"})
    try:
        r = _post("/wx/login", {"phone": phone, "password": pwd})
    except Exception as e:
        return jsonify({"ok": False, "msg": f"请求失败：{type(e).__name__}"})
    if r.get("code") != 10000:
        return jsonify({"ok": False, "msg": r.get("msg") or f"code={r.get('code')}"})
    token = (r.get("data") or {}).get("token")
    if not token:
        return jsonify({"ok": False, "msg": "返回里没有 token"})
    _state["token"] = token
    _state["phone"] = phone
    (DATA / "dd_token.txt").write_text(token, encoding="utf-8")
    return jsonify({"ok": True, "msg": "登录成功"})


@app.get("/api/overview")
def overview():
    if not _state["token"]:
        return jsonify({"ok": False, "msg": "还没登录"})
    out = {"ok": True}
    try:
        u = _get("/wx/userCenter")
        d = u.get("data") or {}
        out["user"] = {
            "nickname": d.get("nickname"),
            "address": d.get("address"),
            "region2": d.get("region_two_id"),
            "region3": d.get("region_three_id"),
            "has_password": bool(d.get("last_edit_password_time")),
        }
    except Exception as e:
        out["user"] = {"error": type(e).__name__}
    try:
        o = (_get("/wx/orderList").get("data") or {})
        out["orders"] = {
            "total": o.get("total"),
            "missed": o.get("missed"),
            "delivered": o.get("delivered"),
            "finished": o.get("finished"),
            "service_phone": o.get("service_phone"),
            "items": (o.get("orderList") or {}).get("data", [])[:3],
        }
    except Exception as e:
        out["orders"] = {"error": type(e).__name__}
    try:
        a = (_get("/wx/addOrder").get("data") or {})
        prods = a.get("productList") or []
        out["products"] = [
            {
                "title": p.get("product_title"),
                "price": p.get("product_price"),
                "stock": p.get("product_stock"),
            }
            for p in prods[:10]
        ]
    except Exception as e:
        out["products"] = [{"title": f"读取失败 {type(e).__name__}", "price": "", "stock": ""}]
    return jsonify(out)


@app.get("/")
def index():
    return PAGE


PAGE = """<!DOCTYPE html>
<html lang="zh"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>DD订水 · 接口验证</title>
<style>
:root{--bg:#141416;--panel:#1c1c1f;--line:#2c2c31;--txt:#e8e8ea;--dim:#9a9aa2;--ok:#1D9E75;--bad:#D85A30}
*{box-sizing:border-box}
body{margin:0;background:var(--bg);color:var(--txt);font:14px/1.6 -apple-system,"Segoe UI","Microsoft YaHei",sans-serif;
display:flex;justify-content:center;padding:48px 20px}
.card{width:100%;max-width:520px;background:var(--panel);border:1px solid var(--line);border-radius:14px;padding:26px 26px 22px}
h1{font-size:16px;font-weight:500;margin:0 0 4px}
.sub{color:var(--dim);font-size:12px;margin-bottom:20px}
label{display:block;font-size:12px;color:var(--dim);margin:12px 0 6px}
input{width:100%;padding:10px 12px;background:#111113;border:1px solid var(--line);border-radius:8px;color:var(--txt);font-size:14px}
input:focus{outline:none;border-color:#534AB7}
button{margin-top:18px;width:100%;padding:11px;background:#3C3489;border:none;border-radius:9px;color:#E8E8EA;
font-size:14px;cursor:pointer}
button:hover{background:#534AB7}
button:disabled{opacity:.5;cursor:default}
.msg{margin-top:14px;font-size:13px;min-height:20px}
.msg.ok{color:var(--ok)} .msg.bad{color:var(--bad)}
.res{margin-top:20px;border-top:1px solid var(--line);padding-top:16px;display:none}
.res.show{display:block}
.kv{display:flex;justify-content:space-between;padding:5px 0;font-size:13px;border-bottom:1px solid #232326}
.kv span:first-child{color:var(--dim)}
h2{font-size:13px;font-weight:500;margin:18px 0 8px;color:var(--dim)}
table{width:100%;border-collapse:collapse;font-size:12.5px}
td{padding:5px 0;border-bottom:1px solid #232326}
td:last-child{text-align:right;color:var(--dim)}
</style></head><body>
<div class="card">
  <h1>DD订水 · 接口验证</h1>
  <div class="sub">用你的手机号和 DD订水 密码登录。密码只发往 ddds.shengzekeji.cn，本机不留存。</div>
  <label>手机号</label>
  <input id="phone" inputmode="numeric" autocomplete="username" placeholder="11 位手机号">
  <label>密码</label>
  <input id="pwd" type="password" autocomplete="current-password" placeholder="DD订水 密码">
  <button id="go">登录并读取</button>
  <div class="msg" id="msg"></div>
  <div class="res" id="res">
    <div id="userBox"></div>
    <h2>订单</h2><div id="orderBox"></div>
    <h2>商品水价</h2><table id="prodBox"></table>
  </div>
</div>
<script>
const $ = id => document.getElementById(id);
$('go').onclick = async () => {
  const phone = $('phone').value.trim(), password = $('pwd').value;
  if (!phone || !password) { $('msg').className='msg bad'; $('msg').textContent='手机号和密码都要填'; return; }
  $('go').disabled = true; $('msg').className='msg'; $('msg').textContent='登录中…';
  try {
    const r = await (await fetch('/api/login', {method:'POST', headers:{'Content-Type':'application/json'},
      body: JSON.stringify({phone, password})})).json();
    if (!r.ok) { $('msg').className='msg bad'; $('msg').textContent = r.msg; $('go').disabled=false; return; }
    $('msg').className='msg ok'; $('msg').textContent = '登录成功，正在读取数据…';
    $('pwd').value = '';
    const o = await (await fetch('/api/overview')).json();
    if (!o.ok) { $('msg').className='msg bad'; $('msg').textContent = o.msg; $('go').disabled=false; return; }
    const u = o.user || {};
    $('userBox').innerHTML = [
      ['昵称', u.nickname], ['地址', u.address],
      ['校区 ID', u.region2 + ' / ' + u.region3],
      ['是否设过密码', u.has_password ? '是' : '否']
    ].map(([k,v]) => `<div class="kv"><span>${k}</span><span>${v ?? '-'}</span></div>`).join('');
    const od = o.orders || {};
    $('orderBox').innerHTML = [
      ['订单总数', od.total], ['待送达', od.missed], ['已送达', od.delivered],
      ['已完成', od.finished], ['客服电话', od.service_phone]
    ].map(([k,v]) => `<div class="kv"><span>${k}</span><span>${v ?? '-'}</span></div>`).join('');
    $('prodBox').innerHTML = (o.products||[]).map(p =>
      `<tr><td>${p.title ?? '-'}</td><td>¥${p.price ?? '-'}　库存 ${p.stock ?? '-'}</td></tr>`).join('');
    $('res').classList.add('show');
    $('msg').textContent = '接口全部可读 ✅';
    $('go').disabled = false;
  } catch (e) {
    $('msg').className='msg bad'; $('msg').textContent = '出错：' + e.message; $('go').disabled=false;
  }
};
</script></body></html>"""


if __name__ == "__main__":
    print("打开 http://127.0.0.1:8899")
    app.run(host="127.0.0.1", port=8899, debug=False)
