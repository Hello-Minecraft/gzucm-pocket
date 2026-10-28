"""分析 getCalendarWeekDatas 的原始返回，验证能否完整还原课表。

读 data/raw_calendar_*.json，输出：
  1. 节次 ↔ 时间映射表（ps/pe vs qssj/jssj）
  2. 解析后的一周课表（第 N 周）
  3. 课程清单
  4. jxcdmc2 解析质量
"""

import json
import re
import sys
from collections import Counter, defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parent
DATA = ROOT / "data"


def load(code: str) -> list:
    path = DATA / f"raw_calendar_{code}.json"
    body = json.loads(path.read_text(encoding="utf-8"))
    return body.get("data") or []


def slot_map(data: list) -> dict:
    """ps/pe → 起止时间，提取作息表"""
    m = defaultdict(Counter)
    for i in data:
        key = (i.get("ps"), i.get("pe"))
        m[key][(i.get("qssj"), i.get("jssj"))] += 1
    return m


def expand_weeks(item: dict) -> tuple[list, list]:
    """返回 (周次列表, 教室串列表)。优先用 jxcdmc2 的逐周教室，回退到 zc"""
    weeks, rooms = [], []
    raw = (item.get("jxcdmc2") or "").strip()
    if raw:
        for tok in raw.split(","):
            tok = tok.strip()
            if not tok:
                continue
            m = re.match(r"^(.*)-(\d{1,2})$", tok)
            if m:
                weeks.append(int(m.group(2)))
                rooms.append(m.group(1).strip() or (item.get("jxcdmc") or "").strip())
            else:
                rooms.append(tok)
    if not weeks:
        for w in (item.get("zc") or "").split(","):
            w = w.strip()
            if w.isdigit():
                weeks.append(int(w))
        rooms = [(item.get("jxcdmc") or "").strip()] * len(weeks)
    return weeks, rooms


def main() -> int:
    code = sys.argv[1] if len(sys.argv) > 1 else "202601"
    data = load(code)
    print(f"学期 {code}：{len(data)} 条排课任务\n")

    # 1. 节次↔时间
    print("## 1. 节次 ↔ 时间（ps/pe 字段）")
    print("| ps | pe | 时间 | 出现次数 |")
    print("|---|---|---|---|")
    sm = slot_map(data)
    rows = []
    for (ps, pe), cnt in sm.items():
        (t1, t2), n = cnt.most_common(1)[0]
        rows.append((str(ps).zfill(2), str(pe).zfill(2), f"{t1}-{t2}", n))
    for r in sorted(rows):
        print("| {} | {} | {} | {} |".format(*r))

    # 2. 展开后的所有上课单元
    units = []
    bad_room = 0
    for i in data:
        weeks, rooms = expand_weeks(i)
        if any(not r for r in rooms):
            bad_room += 1
        for w, r in zip(weeks, rooms):
            units.append({
                "week": w,
                "weekday": int(i.get("xq") or 0),
                "ps": str(i.get("ps")).zfill(2),
                "pe": str(i.get("pe")).zfill(2),
                "time": f"{i.get('qssj','')[:5]}-{i.get('jssj','')[:5]}",
                "course": (i.get("kcmc") or "").strip(),
                "teacher": (i.get("teaxms") or "").strip(),
                "room": r,
                "type": (i.get("jxhjmc") or "").strip(),
                "mode": (i.get("jxmsmc") or "").strip(),
            })

    print(f"\n## 2. 展开结果：{len(units)} 个上课单元（周×课）")
    all_weeks = sorted({u['week'] for u in units if u['week']})
    print(f"覆盖周次：{all_weeks}")
    print(f"教室为空的单元：{bad_room} 条排课")

    # 3. 选一周打印课表
    target = 9
    print(f"\n## 3. 第 {target} 周课表")
    sel = sorted([u for u in units if u["week"] == target], key=lambda x: (x["weekday"], x["ps"]))
    if not sel:
        print(f"（第 {target} 周没有课，换一周）")
        target = all_weeks[len(all_weeks) // 2] if all_weeks else 1
        sel = sorted([u for u in units if u["week"] == target], key=lambda x: (x["weekday"], x["ps"]))
        print(f"改看第 {target} 周")
    wk = "一二三四五六日"
    cur_day = None
    for u in sel:
        if u["weekday"] != cur_day:
            cur_day = u["weekday"]
            print(f"\n周{wk[cur_day - 1] if 1 <= cur_day <= 7 else '?'}")
        print(f"  {u['ps']}-{u['pe']}节 {u['time']}  {u['course']} | {u['teacher']} | {u['room']} | {u['type']}")

    # 4. 课程清单
    print("\n## 4. 课程清单")
    courses = defaultdict(lambda: {"weeks": set(), "rooms": set(), "teacher": "", "type": ""})
    for u in units:
        c = courses[u["course"]]
        c["weeks"].add(u["week"])
        c["rooms"].add(u["room"])
        c["teacher"] = c["teacher"] or u["teacher"]
        c["type"] = c["type"] or u["type"]
    print("| 课程 | 教师 | 类型 | 周次 | 教室数 |")
    print("|---|---|---|---|---|")
    for name, c in sorted(courses.items()):
        ws = sorted(c["weeks"])
        wtxt = f"{ws[0]}-{ws[-1]}（{len(ws)}周）" if ws else "-"
        print(f"| {name} | {c['teacher'][:24]} | {c['type']} | {wtxt} | {len(c['rooms'])} |")

    # 5. 质量检查
    print("\n## 5. 移植可行性检查")
    miss = [u for u in units if not u["room"]]
    print(f"- 缺教室：{len(miss)}/{len(units)}")
    print(f"- 缺星期：{sum(1 for u in units if not (1 <= u['weekday'] <= 7))}")
    print(f"- 缺节次：{sum(1 for u in units if not u['ps'].isdigit() or u['ps'] == '00')}")
    print(f"- 缺周次：{sum(1 for u in units if not u['week'])}")
    print(f"- 课程类型分布：{Counter(u['type'] for u in units)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
