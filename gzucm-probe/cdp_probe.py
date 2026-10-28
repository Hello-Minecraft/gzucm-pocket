"""通过 CDP 连上已登录的 Edge，直接在同源页面里调教务接口并分析字段。

用法：
    python cdp_probe.py                  # 用默认学期调一次
    python cdp_probe.py 202602           # 指定学期
    python cdp_probe.py --inspect        # 只看登录态与学期下拉
"""

import datetime
import json
import re
import sys
import urllib.request
import socket
from pathlib import Path

from playwright.sync_api import sync_playwright

CDP = "http://localhost:9222"
BASE = "https://jw.gzucm.edu.cn"
MAIN_PAGE = BASE + "/new/student/xsgrkb/main.page"

ROOT = Path(__file__).resolve().parent
DATA = ROOT / "data"
DATA.mkdir(exist_ok=True)

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
  const text = await r.text();
  return { status: r.status, text };
}
"""


def wide_range(code: str) -> tuple[str, str]:
    year = int(code[:4])
    if code.endswith("01"):
        anchor, end = datetime.date(year, 8, 1), datetime.date(year + 1, 2, 28)
    else:
        anchor, end = datetime.date(year + 1, 1, 15), datetime.date(year + 1, 8, 31)
    d1 = anchor - datetime.timedelta(days=(anchor.weekday() + 1) % 7)
    return f"{d1:%Y-%m-%d} 00:00:00", f"{end:%Y-%m-%d} 00:00:00"


def guess_code() -> str:
    d = datetime.date.today()
    return f"{d.year}01" if d.month >= 8 else f"{d.year - 1}02"


def analyze(data: list) -> str:
    if not data:
        return "（空列表）\n"
    total = len(data)
    keys: dict = {}
    for item in data:
        if not isinstance(item, dict):
            continue
        for k, v in item.items():
            s = keys.setdefault(k, {"n": 0, "t": set(), "ex": [], "max": 0})
            if v in (None, "", []):
                continue
            s["n"] += 1
            s["t"].add(type(v).__name__)
            sv = str(v)
            s["max"] = max(s["max"], len(sv))
            if sv not in s["ex"] and len(s["ex"]) < 3:
                s["ex"].append(sv[:50])

    out = [f"共 {total} 条，{len(keys)} 字段\n",
           "| 字段 | 类型 | 非空 | 长度 | 示例 |", "|---|---|---|---|---|"]
    for k in sorted(keys, key=lambda x: -keys[x]["n"]):
        s = keys[k]
        out.append(
            f"| `{k}` | {'/'.join(sorted(s['t'])) or '-'} | "
            f"{s['n'] * 100 // total}% | {s['max']} | "
            f"{' ｜ '.join(x.replace('|', '/') for x in s['ex']) or '(全空)'} |"
        )
    return "\n".join(out) + "\n"


def main() -> int:
    args = sys.argv[1:]
    inspect_only = "--inspect" in args
    codes = [a for a in args if re.fullmatch(r"\d{4,8}", a)]
    if not codes:
        codes = [guess_code()]

    socket.setdefaulttimeout(30)
    with sync_playwright() as p:
        browser = p.chromium.connect_over_cdp(CDP)
        ctx = browser.contexts[0]
        page = next((x for x in ctx.pages if "gzucm" in x.url), ctx.pages[0])
        print(f"当前页面：{page.url}")

        if inspect_only:
            cookies = [c["name"] for c in ctx.cookies() if "gzucm" in c.get("domain", "")]
            print(f"Cookie：{cookies}")
            html = page.content()
            opts = re.findall(r'<option[^>]*value=["\'](\d{4,8})["\'][^>]*>([^<]{2,40}?)</option>', html)
            print(f"页面里的学期选项：{opts[:12]}")

        for code in codes[:4]:
            d1, d2 = wide_range(code)
            res = page.evaluate(FETCH_JS, {"code": code, "zc": "", "d1": d1, "d2": d2})
            text = res["text"]
            try:
                body = json.loads(text)
            except json.JSONDecodeError:
                print(f"[{code}] 非 JSON（HTTP {res['status']}）：{text[:200]}")
                continue
            data = body.get("data") or []
            n = len(data) if isinstance(data, list) else -1
            print(f"\n===== 学期 {code} | HTTP {res['status']} | code={body.get('code')} | {n} 条")
            print(f"参数 d1={d1} d2={d2}")
            (DATA / f"raw_calendar_{code}.json").write_text(text, encoding="utf-8")
            if n > 0:
                print("顶层键：", list(body.keys()))
                print(analyze(data))
                # 关键值域
                for f in ("xq", "zc", "jxcdmc2", "qssj", "jssj"):
                    vals = sorted({str(i.get(f)) for i in data if i.get(f)})
                    if vals:
                        print(f"  {f}: {vals[:10]}{' …共%d种' % len(vals) if len(vals) > 10 else ''}")
                print("\n首条完整记录：")
                print(json.dumps(data[0], ensure_ascii=False, indent=2)[:1200])
        return 0


if __name__ == "__main__":
    sys.exit(main())
