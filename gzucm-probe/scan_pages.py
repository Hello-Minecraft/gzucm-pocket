"""在教务框架内点击菜单进入功能页，抓各功能的数据接口。

直接 GET 功能页会被弹回首页，必须在 welcome.page 里通过菜单点击以 iframe 方式打开。
"""

import json
import sys
from pathlib import Path

from playwright.sync_api import sync_playwright

BASE = "https://jw.gzucm.edu.cn/"
DATA = Path(__file__).resolve().parent / "data"
DATA.mkdir(exist_ok=True)

MENUS = [
    "课程成绩",
    "考级成绩",
    "体测成绩",
    "考试安排",
    "选课报名",
    "选订教材",
    "教室借用申请",
]

SKIP = (".js", ".css", ".png", ".jpg", ".gif", ".svg", ".ico", ".woff", ".ttf", ".woff2", ".map")


def interesting(url: str, ct: str) -> bool:
    low = url.lower().split("?")[0]
    if any(low.endswith(e) for e in SKIP):
        return False
    return "json" in ct or "javascript" in ct


def main() -> int:
    out = {}
    with sync_playwright() as p:
        b = p.chromium.connect_over_cdp("http://localhost:9222")
        ctx = b.contexts[0]
        for name in MENUS:
            recs = []
            pg = ctx.new_page()

            def on_resp(r, recs=recs):
                if "jw.gzucm" not in r.url:
                    return
                ct = (r.headers.get("content-type") or "").lower()
                if not interesting(r.url, ct):
                    return
                try:
                    txt = r.text()
                except Exception:
                    return
                recs.append({
                    "method": r.request.method,
                    "path": r.url.replace(BASE, "/").split("?")[0],
                    "query": (r.url.split("?")[1][:120] if "?" in r.url else ""),
                    "post": (r.request.post_data or "")[:200],
                    "status": r.status,
                    "len": len(txt),
                    "head": txt[:300],
                })

            pg.on("response", on_resp)
            pg.goto(BASE + "new/welcome.page?ui=new", wait_until="networkidle", timeout=45000)
            pg.wait_for_timeout(1200)
            recs.clear()
            hit = pg.evaluate(
                """(t) => {
                    const a = Array.from(document.querySelectorAll('a.menuItem'))
                        .find(x => (x.textContent||'').trim() === t);
                    if (!a) return 'NOT_FOUND';
                    a.click(); return a.getAttribute('href') || 'ok';
                }""",
                name,
            )
            pg.wait_for_timeout(4000)
            frames = [f.url.replace(BASE, "/") for f in pg.frames if "gzucm" in f.url]
            out[name] = {"menu": hit, "frames": frames, "api": recs}
            print(f"\n===== {name}   菜单→{hit}")
            for f in frames:
                if "welcome.page" not in f and "/new/desktop" not in f:
                    print(f"   frame: {f[:90]}")
            seen = set()
            for r in recs:
                k = (r["method"], r["path"])
                if k in seen:
                    continue
                seen.add(k)
                flag = "  ← 数据" if r["len"] > 50 and ("get" in r["path"].lower() or "list" in r["path"].lower() or "query" in r["path"].lower()) else ""
                print(f"   {r['method']:5} {r['path']:48} {r['status']} {r['len']:>6}B{flag}")
                if r["post"]:
                    print(f"         post: {r['post'][:110]}")
            pg.close()
    (DATA / "pages_api.json").write_text(json.dumps(out, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"\n写入 {DATA / 'pages_api.json'}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
