"""探测学期/周次相关的辅助接口，找"第一周周一"的来源。"""

import json
import re
import sys
from pathlib import Path

from playwright.sync_api import sync_playwright

DATA = Path(__file__).resolve().parent / "data"
DATA.mkdir(exist_ok=True)

GET_JS = """
async (url) => {
  const r = await fetch(url, {credentials:'include', headers:{'X-Requested-With':'XMLHttpRequest'}});
  const t = await r.text();
  return {status: r.status, ct: r.headers.get('content-type') || '', text: t};
}
"""

CANDIDATES = [
    "/new/api/semester",
    "/new/student/xsgrkb/main.page",
    "/new/student/xsgrkb/getXnXq",
    "/new/api/xnxq",
    "/new/index.page",
    "/new/welcome.page?ui=new",
]


def main() -> int:
    with sync_playwright() as p:
        b = p.chromium.connect_over_cdp("http://localhost:9222")
        ctx = b.contexts[0]
        page = next((x for x in ctx.pages if "gzucm" in x.url), ctx.pages[0])

        for path in CANDIDATES:
            try:
                res = page.evaluate(GET_JS, path)
            except Exception as e:
                print(f"{path:40} 执行失败 {e}")
                continue
            txt = res["text"]
            head = txt[:160].replace("\n", " ")
            print(f"\n=== {path}  HTTP {res['status']}  {len(txt)}B  [{res['ct'][:40]}]")
            print(f"    {head}")

            name = re.sub(r"[^a-zA-Z0-9]", "_", path.strip("/"))[:40]
            (DATA / f"probe_{name}.txt").write_text(txt, encoding="utf-8")

            # HTML 里找学期下拉与周次信息
            if "<option" in txt:
                opts = re.findall(
                    r'<option[^>]*value=["\'](\d{4,8})["\'][^>]*>\s*([^<]{2,40}?)\s*</option>', txt)
                if opts:
                    print(f"    学期选项：{opts[:12]}")
            for kw in ("教学周", "第", "周次", "周历", "开始日期", "结束日期"):
                for m in re.finditer(kw, txt):
                    s = max(0, m.start() - 40)
                    seg = txt[s:m.start() + 40].replace("\n", " ")
                    if any(d in seg for d in ("2026", "2027")) and "周" in seg:
                        print(f"    [周历线索] …{seg}…")
                        break
                else:
                    continue
                break
    return 0


if __name__ == "__main__":
    sys.exit(main())
