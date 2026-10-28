"""验证「按周查询」在整个学期都有效：逐周请求，统计上课内容覆盖率。

用途：确认 `zc=周次` 这条路不是只有第 8 周能用，
并且统计有多少节课带 sknrjj（上课内容），多少带 qsrq（真实日期）。

用法：python verify_weeks.py [学期代码]
"""

import json
import re
import sys
import time
from pathlib import Path

from playwright.sync_api import sync_playwright

BASE = "https://jw.gzucm.edu.cn"
MAIN = BASE + "/new/student/xsgrkb/main.page"
SSO = BASE + "/new/ssoLogin"
PROFILE = r"D:\App\_android\edge-cdp"
ROOT = Path(__file__).resolve().parent
DATA = ROOT / "data"
DATA.mkdir(exist_ok=True)
LOG = ROOT / "verify_weeks.log"


def log(msg):
    print(msg, flush=True)
    try:
        with open(LOG, "a", encoding="utf-8") as f:
            f.write(str(msg) + "\n")
    except Exception:
        pass

FETCH_JS = """
async (args) => {
  const body = new URLSearchParams({
    xnxqdm: args.code, zc: String(args.zc), d1: args.d1, d2: args.d2
  });
  const r = await fetch('/new/student/xsgrkb/getCalendarWeekDatas', {
    method: 'POST', credentials: 'include',
    headers: {'Content-Type':'application/x-www-form-urlencoded; charset=UTF-8',
              'X-Requested-With':'XMLHttpRequest'},
    body
  });
  return await r.text();
}
"""

CHECK_JS = """
async () => {
  const r = await fetch('/new/xlxx/data', {
    method:'POST', credentials:'include',
    headers:{'Content-Type':'application/x-www-form-urlencoded; charset=UTF-8',
             'X-Requested-With':'XMLHttpRequest'},
    body: new URLSearchParams({})
  });
  const t = await r.text();
  let j=null; try{j=JSON.parse(t)}catch(e){}
  return j ? j.code : null;
}
"""


def main():
    code = sys.argv[1] if len(sys.argv) > 1 else "202601"
    d1, d2 = "2026-08-01 00:00:00", "2027-02-28 00:00:00"

    with sync_playwright() as p:
        ctx = p.chromium.launch_persistent_context(
            PROFILE, channel="msedge", headless=False,
            viewport={"width": 1280, "height": 900},
            args=["--no-first-run", "--no-default-browser-check"],
        )
        page = ctx.pages[0] if ctx.pages else ctx.new_page()
        page.goto(MAIN, wait_until="domcontentloaded", timeout=60000)

        c = page.evaluate(CHECK_JS)
        log(f"[*] 登录态 code={c}  url={page.url}")
        if c in (-401, 401):
            log("[*] 会话失效，跳转 SSO，请手动登录…")
            page.goto(SSO, wait_until="domcontentloaded", timeout=60000)
            start = time.time()
            ok = False
            while time.time() - start < 600:
                try:
                    cc = page.evaluate(CHECK_JS)
                    if cc not in (-401, 401, None):
                        ok = True
                        log(f"[+] 登录成功 code={cc}")
                        break
                except Exception:
                    pass
                if int(time.time() - start) % 20 == 0:
                    log(f"[…] 等待登录 {int(time.time()-start)}s")
                time.sleep(4)
            if not ok:
                log("[-] 超时未登录")
                ctx.close()
                return
            page.goto(MAIN, wait_until="domcontentloaded", timeout=60000)

        total_rows = 0
        with_content = 0
        with_date = 0
        with_teacher = 0
        per_week = {}

        for w in range(1, 20):
            txt = page.evaluate(FETCH_JS, {"code": code, "zc": w, "d1": d1, "d2": d2})
            try:
                rows = json.loads(txt).get("data") or []
            except Exception:
                rows = []
            if not isinstance(rows, list):
                rows = []
            per_week[w] = len(rows)
            total_rows += len(rows)
            for r in rows:
                if (r.get("sknrjj") or "").strip():
                    with_content += 1
                if (r.get("qsrq") or "").strip():
                    with_date += 1
                if (r.get("teaxms") or "").strip():
                    with_teacher += 1
            print(f"  第 {w:2} 周: {len(rows):3} 条")

        (DATA / "weeks_summary.json").write_text(
            json.dumps(per_week, ensure_ascii=False, indent=2), encoding="utf-8")

        print("\n===== 汇总 =====")
        print(f"总课次       : {total_rows}")
        print(f"有上课内容   : {with_content} ({with_content*100//max(total_rows,1)}%)")
        print(f"有真实日期   : {with_date} ({with_date*100//max(total_rows,1)}%)")
        print(f"有授课教师   : {with_teacher} ({with_teacher*100//max(total_rows,1)}%)")
        empty = [w for w, n in per_week.items() if n == 0]
        print(f"空周         : {empty if empty else '无'}")

        ctx.close()


if __name__ == "__main__":
    main()
