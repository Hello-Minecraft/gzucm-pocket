"""抓取教务「按周课表」数据，对比「全量」与「单周」两种响应的字段差异。

背景：App 现在用 getCalendarWeekDatas 的 zc="" 拿整学期（粗粒度），
但课表查询页选某周后会返回更细的数据（授课老师、上课内容）。
这个脚本负责验证：同一个接口加 zc 参数是否就能拿到细字段，
还是要换接口/加参数。

用法：
    python probe_week.py            # 抓当前周 + 全量，做对比
    python probe_week.py 8          # 指定第 8 周

启动后会打开浏览器，如果没登录就等你手动登录，登录后自动继续。
"""

import json
import re
import sys
import time
from pathlib import Path

from playwright.sync_api import sync_playwright

BASE = "https://jw.gzucm.edu.cn"
MAIN = BASE + "/new/student/xsgrkb/main.page"
# App 里用的就是这条入口：会触发 CAS 跳转到统一身份认证登录页
SSO = BASE + "/new/ssoLogin"
PROFILE = r"D:\App\_android\edge-cdp"
ROOT = Path(__file__).resolve().parent
DATA = ROOT / "data"
DATA.mkdir(exist_ok=True)

# 在页面上下文里发出课表请求。zc 传空 = 全学期，传数字 = 单周。
FETCH_JS = """
async (args) => {
  const body = new URLSearchParams({
    xnxqdm: args.code, zc: args.zc || '', d1: args.d1, d2: args.d2
  });
  const r = await fetch('/new/student/xsgrkb/getCalendarWeekDatas', {
    method: 'POST',
    credentials: 'include',
    headers: {
      'Content-Type': 'application/x-www-form-urlencoded; charset=UTF-8',
      'X-Requested-With': 'XMLHttpRequest'
    },
    body
  });
  const t = await r.text();
  return { status: r.status, body: t };
}
"""


LOG = ROOT / "probe_week.log"


def log(msg):
    """同时打到 stdout 和日志文件，方便外部轮询进度。"""
    print(msg, flush=True)
    try:
        with open(LOG, "a", encoding="utf-8") as f:
            f.write(str(msg) + "\n")
    except Exception:
        pass


# 用接口返回值判断登录态：-401 就是没登录。
CHECK_JS = """
async () => {
  const r = await fetch('/new/xlxx/data', {
    method: 'POST', credentials: 'include',
    headers: {'Content-Type':'application/x-www-form-urlencoded; charset=UTF-8',
              'X-Requested-With':'XMLHttpRequest'},
    body: new URLSearchParams({})
  });
  const t = await r.text();
  let j = null;
  try { j = JSON.parse(t); } catch (e) {}
  return { status: r.status, code: j ? j.code : null, body: t.substring(0, 200) };
}
"""


def wait_login(page, timeout=600):
    """等用户手动登录：调接口，返回码不是 -401 才算真的登录。"""
    log("[*] 检查登录状态（请在浏览器里完成登录）…")
    start = time.time()
    last_note = 0
    while time.time() - start < timeout:
        try:
            r = page.evaluate(CHECK_JS)
            code = r.get("code")
            if code is not None and code != -401 and code != 401:
                log(f"[+] 已登录（接口 code={code}）")
                return True
        except Exception as e:
            log(f"[!] 检查异常: {e}")

        elapsed = int(time.time() - start)
        if elapsed - last_note >= 15:
            last_note = elapsed
            log(f"[…] 等待登录 {elapsed}s / {timeout}s")
        time.sleep(4)
    log("[-] 超时，未检测到登录")
    return False


def get_semester(page):
    """取当前学期代码与第一周周一。"""
    info = page.evaluate(
        """async () => {
            const r = await fetch('/new/xlxx/data', {
                method:'POST', credentials:'include',
                headers:{'Content-Type':'application/x-www-form-urlencoded; charset=UTF-8',
                         'X-Requested-With':'XMLHttpRequest'},
                body: new URLSearchParams({})
            });
            const t = await r.text();
            return t.substring(0, 4000);
        }"""
    )
    return info


def main():
    week = sys.argv[1] if len(sys.argv) > 1 else "8"
    try:
        LOG.unlink()
    except Exception:
        pass

    with sync_playwright() as p:
        ctx = p.chromium.launch_persistent_context(
            PROFILE,
            channel="msedge",
            headless=False,
            viewport={"width": 1280, "height": 900},
            args=["--no-first-run", "--no-default-browser-check"],
        )
        page = ctx.pages[0] if ctx.pages else ctx.new_page()
        log("[*] 打开统一身份认证入口…")
        # 先探一次是否已有会话；没有就直奔 SSO，登录框直接出来
        page.goto(MAIN, wait_until="domcontentloaded", timeout=60000)
        time.sleep(2)
        try:
            probe = page.evaluate(CHECK_JS)
        except Exception:
            probe = {"code": None}
        if probe.get("code") in (-401, 401) or probe.get("code") is None:
            log("[*] 无有效会话，跳转 SSO 登录…")
            page.goto(SSO, wait_until="domcontentloaded", timeout=60000)
        log(f"[*] 当前 URL: {page.url}")

        if not wait_login(page):
            ctx.close()
            return

        log("[*] 重新载入课表页…")
        page.goto(MAIN, wait_until="domcontentloaded", timeout=60000)

        sem_raw = get_semester(page)
        (DATA / "semester_probe.json").write_text(sem_raw, encoding="utf-8")
        log("[*] xlxx 响应前 400 字: " + sem_raw[:400])

        # 取学期代码：优先页面下拉，其次从响应里挖
        code = ""
        try:
            code = page.evaluate(
                """() => {
                    const sel = document.querySelector('select[name=xnxqdm], #xnxqdm');
                    return sel ? sel.value : '';
                }"""
            ) or ""
        except Exception:
            pass
        if not code:
            m = re.search(r'"xnxqdm"\s*:\s*"(\d{6})"', sem_raw)
            code = m.group(1) if m else "202601"
        log(f"[*] 学期代码: {code}")

        # 日期范围给宽，避免边界漏课
        d1 = "2026-08-01 00:00:00"
        d2 = "2027-02-28 00:00:00"

        log("[*] 请求全量（zc 为空）…")
        all_res = page.evaluate(FETCH_JS, {"code": code, "zc": "", "d1": d1, "d2": d2})
        (DATA / "week_all.json").write_text(all_res["body"], encoding="utf-8")
        log(f"    HTTP {all_res['status']} 长度 {len(all_res['body'])}")

        log(f"[*] 请求单周（zc={week}）…")
        one_res = page.evaluate(FETCH_JS, {"code": code, "zc": week, "d1": d1, "d2": d2})
        (DATA / f"week_{week}.json").write_text(one_res["body"], encoding="utf-8")
        log(f"    HTTP {one_res['status']} 长度 {len(one_res['body'])}")

        # ── 对比字段 ──
        def rows(txt):
            try:
                j = json.loads(txt)
            except Exception:
                return None, None
            arr = j.get("data") or []
            if not isinstance(arr, list):
                arr = []
            return j, arr

        j_all, arr_all = rows(all_res["body"])
        j_one, arr_one = rows(one_res["body"])

        log("\n===== 对比结果 =====")
        log(f"全量条数: {len(arr_all) if arr_all is not None else '解析失败'}")
        log(f"单周条数: {len(arr_one) if arr_one is not None else '解析失败'}")

        if arr_all and arr_one:
            k_all = set(arr_all[0].keys())
            k_one = set(arr_one[0].keys())
            log(f"\n全量字段数 {len(k_all)}，单周字段数 {len(k_one)}")
            log("\n【只在单周里有】" + str(sorted(k_one - k_all)))
            log("\n【只在全量里有】" + str(sorted(k_all - k_one)))

            log("\n===== 单周第一条完整数据 =====")
            log(json.dumps(arr_one[0], ensure_ascii=False, indent=2)[:3000])

            log("\n===== 单周所有非空字段 =====")
            for k, v in arr_one[0].items():
                s = str(v)
                if s and s not in ("None", "null"):
                    log(f"  {k:20} = {s[:80]}")
        else:
            log("[!] 数据为空，原始响应：")
            log(all_res["body"][:300])
            log(one_res["body"][:300])

        try:
            page.screenshot(path=str(DATA / "week_page.png"))
        except Exception:
            pass
        log(f"[*] 已保存 data/week_all.json 与 data/week_{week}.json")
        time.sleep(3)
        ctx.close()


if __name__ == "__main__":
    main()
