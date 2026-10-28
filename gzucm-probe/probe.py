"""
广中医教务接口验证脚本 —— 为移植 ZZUPocket 做字段级确认

做的事情：
  1. 弹出一个 Edge 窗口，你手动登录（学号 + 密码 + 滑一次验证码）
  2. 脚本轮询检测登录是否成功，成功后接管会话 Cookie
  3. 拉课表页、解析学期下拉、调 getCalendarWeekDatas
  4. 输出字段字典与原始 JSON，判断 ZZUPocket 需要的字段齐不齐

用法：
    python probe.py

产物（都在 data/ 目录）：
    raw_calendar_<学期>.json   接口原始返回
    main_page.html             课表页 HTML（含学期下拉）
    cookies.json               会话 Cookie（敏感，勿外传）
    fields_report.md           字段实测报告
"""

import datetime
import json
import re
import sys
import time
from pathlib import Path

from playwright.sync_api import sync_playwright

BASE = "https://jw.gzucm.edu.cn"
LOGIN_PAGE = BASE + "/"
CAL_URL = BASE + "/new/student/xsgrkb/getCalendarWeekDatas"
MAIN_PAGE = BASE + "/new/student/xsgrkb/main.page"

ROOT = Path(__file__).resolve().parent
DATA = ROOT / "data"
DATA.mkdir(exist_ok=True)
PROFILE = ROOT / ".edge-profile"

UA = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36 Edg/131.0.0.0"
)


def log(msg: str) -> None:
    print(msg, flush=True)


def guess_semester_code(today: datetime.date | None = None) -> str:
    """8 月及以后算当年第一学期(01)，否则算上一年第二学期(02)"""
    d = today or datetime.date.today()
    return f"{d.year}01" if d.month >= 8 else f"{d.year - 1}02"


def wide_range(code: str) -> tuple[str, str]:
    """给一个足够宽的日期区间，看接口一次能返回多少周"""
    year = int(code[:4])
    if code.endswith("01"):
        anchor = datetime.date(year, 8, 1)
        end = datetime.date(year + 1, 2, 28)
    else:
        anchor = datetime.date(year + 1, 1, 15)
        end = datetime.date(year + 1, 8, 31)
    d1 = anchor - datetime.timedelta(days=(anchor.weekday() + 1) % 7)
    return f"{d1:%Y-%m-%d} 00:00:00", f"{end:%Y-%m-%d} 00:00:00"


def post_calendar(ctx, code: str, d1: str, d2: str, zc: str = ""):
    headers = {
        "Referer": MAIN_PAGE,
        "X-Requested-With": "XMLHttpRequest",
        "Origin": BASE,
    }
    resp = ctx.request.post(
        CAL_URL,
        form={"xnxqdm": code, "zc": zc, "d1": d1, "d2": d2},
        headers=headers,
        timeout=30000,
    )
    text = resp.text()
    try:
        return resp.status, json.loads(text), text
    except json.JSONDecodeError:
        return resp.status, None, text


def parse_semester_options(html: str) -> list[tuple[str, str]]:
    """从课表页 HTML 里挖学期下拉 <option value=...>文本</option>"""
    out = []
    for m in re.finditer(
        r'<option[^>]*value=["\'](\d{4,8})["\'][^>]*>\s*([^<]{2,40}?)\s*</option>', html
    ):
        value, label = m.group(1), m.group(2).strip()
        if value not in [v for v, _ in out]:
            out.append((value, label))
    return out


def analyze(data: list[dict]) -> str:
    """字段字典 + 值域分析，输出 markdown 片段"""
    if not data:
        return "接口返回空列表，无法分析字段。\n"

    total = len(data)
    keys: dict[str, dict] = {}
    for item in data:
        if not isinstance(item, dict):
            continue
        for k, v in item.items():
            slot = keys.setdefault(
                k, {"nonnull": 0, "types": set(), "samples": [], "len_max": 0}
            )
            if v is None or v == "" or v == []:
                continue
            slot["nonnull"] += 1
            slot["types"].add(type(v).__name__)
            s = str(v)
            slot["len_max"] = max(slot["len_max"], len(s))
            if s not in slot["samples"] and len(slot["samples"]) < 3:
                slot["samples"].append(s[:60])

    lines = [
        f"共 {total} 条记录，{len(keys)} 个字段。\n",
        "| 字段 | 类型 | 非空率 | 最大长度 | 示例值 |",
        "|---|---|---|---|---|",
    ]
    for k in sorted(keys, key=lambda x: -keys[x]["nonnull"]):
        s = keys[k]
        rate = f"{s['nonnull'] * 100 / total:.0f}%"
        types = "/".join(sorted(s["types"])) or "-"
        sample = " ｜ ".join(x.replace("|", "/") for x in s["samples"]) or "(全空)"
        lines.append(f"| `{k}` | {types} | {rate} | {s['len_max']} | {sample} |")

    # 关键字段值域
    lines.append("\n### 关键字段值域\n")
    for field, desc in [
        ("xq", "星期"),
        ("zc", "周次原始串"),
        ("jxcdmc2", "逐周教室串"),
        ("qssj", "开始时间"),
        ("jssj", "结束时间"),
    ]:
        vals = sorted({str(i.get(field)) for i in data if i.get(field)})
        if not vals:
            lines.append(f"- `{field}`（{desc}）：**全部为空或不存在**")
            continue
        show = vals[:12]
        more = f" …共 {len(vals)} 种" if len(vals) > 12 else ""
        lines.append(f"- `{field}`（{desc}）：{show}{more}")

    # jxcdmc2 结构拆解
    tokens = []
    for i in data:
        raw = i.get("jxcdmc2") or ""
        tokens.extend([t.strip() for t in str(raw).split(",") if t.strip()])
    if tokens:
        tail_num = [t for t in tokens if re.search(r"-\d{1,2}$", t)]
        lines.append(
            f"\n`jxcdmc2` 拆出 {len(tokens)} 个教室-周次片段，"
            f"其中 {len(tail_num)} 个符合「教室名-周次」格式。示例：{tokens[:4]}"
        )

    # 一周覆盖情况（用 qssj 判断不了，用周次尾部数字统计）
    weeks = set()
    for t in tokens:
        m = re.search(r"-(\d{1,2})$", t)
        if m:
            weeks.add(int(m.group(1)))
    if weeks:
        lines.append(f"\n从 `jxcdmc2` 提取到教学周号：{sorted(weeks)}（共 {len(weeks)} 周）")

    return "\n".join(lines) + "\n"


def mapping_check(data: list[dict]) -> str:
    """ZZUPocket 的 domain.Session 需要哪些字段，广中医这边够不够"""
    sample = next((i for i in data if isinstance(i, dict)), {})
    checks = [
        ("课程名 courseName", ["kcmc", "kcbm", "kcmc2"]),
        ("教师 teacher", ["teaxms", "jsxm", "teaName"]),
        ("星期 weekday", ["xq", "xqj", "weekday"]),
        ("开始时间 startTime", ["qssj", "startTime"]),
        ("结束时间 endTime", ["jssj", "endTime"]),
        ("教室 room", ["jxcdmc", "jxcdmc2", "cdmc"]),
        ("教学周 weekIndex", ["zc", "jxcdmc2", "zcd"]),
        ("具体日期 date", ["rq", "date", "skrq"]),
        ("节次 startUnit", ["qsjc", "startUnit", "jcs"]),
    ]
    lines = ["| ZZUPocket 需要的 | 广中医字段 | 状态 |", "|---|---|---|"]
    for need, keys in checks:
        hit = next((k for k in keys if k in sample and sample.get(k) not in (None, "")), None)
        status = f"✅ `{hit}`" if hit else "❌ 需自行推导"
        lines.append(f"| {need} | {hit or '—'} | {status} |")
    return "\n".join(lines)


def main() -> int:
    log("=" * 62)
    log("广中医教务接口验证 · 为移植 ZZUPocket 做字段确认")
    log("=" * 62)
    log("马上会弹出 Edge 窗口，请在里面用学号密码登录，滑一次验证码。")
    log("登录成功后脚本会自动接管，不用再管浏览器。\n")

    with sync_playwright() as p:
        ctx = p.chromium.launch_persistent_context(
            user_data_dir=str(PROFILE),
            channel="msedge",
            headless=False,
            no_viewport=True,
            args=["--start-maximized", "--disable-blink-features=AutomationControlled"],
        )
        page = ctx.pages[0] if ctx.pages else ctx.new_page()

        # 先探一下是否已有可用会话
        code0 = guess_semester_code()
        d1, d2 = wide_range(code0)
        status, body, raw = post_calendar(ctx, code0, d1, d2)
        already = isinstance(body, dict) and body.get("code") == 0

        if not already:
            page.goto(LOGIN_PAGE, wait_until="domcontentloaded")
            log("已打开登录页，等你登录……（最多等 10 分钟）")
            deadline = time.time() + 600
            ok = False
            while time.time() < deadline:
                time.sleep(2)
                try:
                    if page.is_closed():
                        log("浏览器被关闭，退出。")
                        return 2
                    status, body, raw = post_calendar(ctx, code0, d1, d2)
                    if isinstance(body, dict) and body.get("code") == 0:
                        ok = True
                        break
                except Exception:
                    continue
            if not ok:
                log("10 分钟内没检测到登录成功，退出。")
                return 3
        log("✅ 会话有效\n")

        # 存 Cookie
        cookies = ctx.cookies()
        names = [c["name"] for c in cookies if "gzucm" in c.get("domain", "")]
        (DATA / "cookies.json").write_text(
            json.dumps({"cookies": cookies}, ensure_ascii=False, indent=2), encoding="utf-8"
        )
        log(f"会话 Cookie（仅名字）：{names}")

        # 课表页 → 学期下拉
        semesters: list[tuple[str, str]] = []
        try:
            r = ctx.request.get(MAIN_PAGE, timeout=30000)
            html = r.text()
            (DATA / "main_page.html").write_text(html, encoding="utf-8")
            semesters = parse_semester_options(html)
            log(f"课表页 {len(html)} 字符，解析到 {len(semesters)} 个学期选项")
            for v, label in semesters[:10]:
                log(f"    {v}  {label}")
        except Exception as e:
            log(f"课表页抓取失败：{e}")

        targets = [v for v, _ in semesters if re.fullmatch(r"\d{4,8}", v)] or [code0]

        report = [
            "# 广中医教务接口实测报告\n",
            f"生成时间：{datetime.datetime.now():%Y-%m-%d %H:%M:%S}\n",
            f"会话 Cookie：{names}\n",
        ]

        best: list[dict] = []
        best_code = ""
        for code in targets[:4]:
            d1, d2 = wide_range(code)
            status, body, raw = post_calendar(ctx, code, d1, d2)
            n = len(body.get("data") or []) if isinstance(body, dict) else -1
            log(f"学期 {code}：HTTP {status}，code={body.get('code') if isinstance(body,dict) else '?'}，{n} 条")
            (DATA / f"raw_calendar_{code}.json").write_text(raw, encoding="utf-8")
            report.append(
                f"\n## 学期 {code}\n\n- 请求：`xnxqdm={code}&zc=&d1={d1}&d2={d2}`\n"
                f"- HTTP {status}，返回 {n} 条\n"
            )
            if isinstance(body, dict) and isinstance(body.get("data"), list) and len(body["data"]) > len(best):
                best = body["data"]
                best_code = code
                report.append(f"- 顶层键：{list(body.keys())}\n")

        if not best:
            log("❌ 没有拿到任何课表数据，看看 data/raw_calendar_*.json 里是什么")
            (DATA / "fields_report.md").write_text("\n".join(report), encoding="utf-8")
            return 4

        log(f"\n用学期 {best_code} 的 {len(best)} 条记录做字段分析")
        report.append(f"\n## 字段字典（学期 {best_code}，{len(best)} 条）\n")
        report.append(analyze(best))
        report.append("\n## ZZUPocket 字段映射可行性\n")
        report.append(mapping_check(best))
        (DATA / "fields_report.md").write_text("\n".join(report), encoding="utf-8")

        log("\n" + "-" * 62)
        log(analyze(best))
        log("-" * 62)
        log(mapping_check(best))
        log(f"\n报告已写入：{DATA / 'fields_report.md'}")
        log("原始 JSON：" + ", ".join(x.name for x in DATA.glob("raw_calendar_*.json")))

        ctx.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
