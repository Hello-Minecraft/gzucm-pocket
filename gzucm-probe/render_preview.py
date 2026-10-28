"""把 getCalendarWeekDatas 的原始返回渲染成课表 HTML，用于肉眼验收数据还原度。"""

import json
import re
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parent
DATA = ROOT / "data"
CODE = "202601"
SEMESTER_START = "2026-09-07"  # 第 1 周周一
WEEKDAYS = ["周一", "周二", "周三", "周四", "周五", "周六", "周日"]

PALETTE = ["#7F77DD", "#1D9E75", "#D85A30", "#D4537E", "#378ADD",
           "#639922", "#BA7517", "#993C1D", "#993556"]


def color_of(name: str) -> str:
    return PALETTE[sum(ord(c) for c in name) % len(PALETTE)]


def expand(item: dict) -> list:
    """一条排课任务 → 多个 (周, 教室)"""
    out = []
    raw = (item.get("jxcdmc2") or "").strip()
    main_room = (item.get("jxcdmc") or "").strip()
    if raw:
        for tok in raw.split(","):
            tok = tok.strip()
            if not tok:
                continue
            m = re.match(r"^(.*)-(\d{1,2})$", tok)
            if m:
                out.append((int(m.group(2)), m.group(1).strip() or main_room))
            else:
                out.append((None, tok))
    if not out:
        for w in (item.get("zc") or "").split(","):
            if w.strip().isdigit():
                out.append((int(w.strip()), main_room))
    return out


def build_units(data: list) -> list:
    units = []
    for i in data:
        for week, room in expand(i):
            if not week:
                continue
            units.append({
                "week": week,
                "wd": int(i.get("xq") or 0),
                "ps": int(str(i.get("ps")).lstrip("0") or 0),
                "pe": int(str(i.get("pe")).lstrip("0") or 0),
                "t1": (i.get("qssj") or "")[:5],
                "t2": (i.get("jssj") or "")[:5],
                "course": (i.get("kcmc") or "").strip(),
                "teacher": (i.get("teaxms") or "").strip(),
                "room": room,
                "type": (i.get("jxhjmc") or "").strip(),
            })
    return units


def render(units: list, weeks: list) -> str:
    blocks = defaultdict(list)
    for u in units:
        if u["pe"] < u["ps"]:
            u["pe"] = u["ps"]
        blocks[(u["week"], u["wd"])].append(u)

    # 节次行：出现的 ps 与 pe 全部纳入，跳号如实保留
    slot_time = {}
    for u in units:
        slot_time.setdefault(u["ps"], u["t1"])
    slots = sorted(set(slot_time) | {u["pe"] for u in units})
    row_of = {ps: i + 1 for i, ps in enumerate(slots)}

    week_data = {}
    for w in weeks:
        cells = {}
        for wd in range(1, 8):
            items = sorted(blocks.get((w, wd), []), key=lambda x: x["ps"])
            cells[wd] = [{
                "course": u["course"], "room": u["room"],
                "ps": u["ps"], "pe": u["pe"], "t1": u["t1"], "t2": u["t2"],
                "color": color_of(u["course"]),
                "row": row_of[u["ps"]],
                "span": row_of[u["pe"]] - row_of[u["ps"]] + 1,
            } for u in items]
        week_data[w] = cells

    slot_labels = [[ps, slot_time.get(ps, "")] for ps in slots]

    return f"""<!DOCTYPE html>
<html lang="zh"><head><meta charset="utf-8">
<title>23铁涛班 · 2026 秋季学期课表（真实数据）</title>
<style>
:root {{ --bg:#141416; --panel:#1c1c1f; --line:#2c2c31; --txt:#e8e8ea; --dim:#9a9aa2; }}
* {{ box-sizing:border-box; }}
body {{ margin:0; background:var(--bg); color:var(--txt);
  font:14px/1.5 -apple-system,"Segoe UI","Microsoft YaHei",sans-serif; padding:20px 24px 32px; }}
h1 {{ font-size:17px; font-weight:500; margin:0 0 4px; }}
.sub {{ color:var(--dim); font-size:12px; margin-bottom:16px; }}
.weeks {{ display:flex; flex-wrap:wrap; gap:6px; margin-bottom:16px; }}
.wk {{ padding:5px 11px; border-radius:7px; background:var(--panel);
  border:1px solid var(--line); cursor:pointer; font-size:12px; color:var(--dim); }}
.wk.on {{ background:#2f2a52; border-color:#7F77DD; color:#CECBF6; }}
.grid {{ display:grid; grid-template-columns:56px repeat(7,1fr); gap:1px;
  background:var(--line); border:1px solid var(--line); border-radius:10px; overflow:hidden; }}
.hd {{ background:#202024; padding:8px 4px; text-align:center; font-size:12px; color:var(--dim); }}
.slot {{ background:#1a1a1d; padding:5px 4px; text-align:center; font-size:11px; color:#6f6f78;
  display:flex; flex-direction:column; justify-content:center; }}
.slot.for {{ background:#141416; }}
.slot b {{ font-weight:500; font-size:12px; color:#9a9aa2; }}
.ev {{ border-radius:6px; padding:5px 7px; font-size:11.5px; line-height:1.35;
  overflow:hidden; align-self:stretch; }}
.ev b {{ display:block; font-weight:500; font-size:12px; margin-bottom:3px;
  white-space:nowrap; overflow:hidden; text-overflow:ellipsis; }}
.ev span {{ display:block; color:#e8e8eab0; white-space:nowrap;
  overflow:hidden; text-overflow:ellipsis; }}
.ev .t {{ font-size:10.5px; opacity:.7; }}
.foot {{ margin-top:14px; color:var(--dim); font-size:11.5px; }}
</style></head><body>
<h1>23 铁涛班 · 2026-2027 学年第一学期</h1>
<div class="sub">数据源：jw.gzucm.edu.cn /new/student/xsgrkb/getCalendarWeekDatas ｜
{len(units)} 个上课单元 ｜ 第 1 周周一 {SEMESTER_START} ｜ 节次号按接口 ps/pe 原样呈现（有跳号）</div>
<div class="weeks" id="weeks"></div>
<div class="grid" id="grid"></div>
<div class="foot" id="foot"></div>
<script>
const DATA = {json.dumps(week_data, ensure_ascii=False)};
const SLOTS = {json.dumps(slot_labels, ensure_ascii=False)};
const WD = {json.dumps(WEEKDAYS, ensure_ascii=False)};
const ROWH = 46;
let cur = 8;

function draw(w) {{
  const g = document.getElementById('grid');
  g.style.gridTemplateRows = '36px repeat(' + SLOTS.length + ',' + ROWH + 'px)';
  g.innerHTML = '';
  const h = document.createElement('div');
  h.className = 'hd'; h.textContent = '节次'; g.appendChild(h);
  WD.forEach(d => {{
    const e = document.createElement('div');
    e.className = 'hd'; e.textContent = d; g.appendChild(e);
  }});
  SLOTS.forEach(([ps, t], i) => {{
    const s = document.createElement('div');
    s.className = 'slot'; s.style.gridRow = (i + 2);
    s.innerHTML = '<b>' + ps + '</b>' + (t ? '<span style="font-size:10px;opacity:.75">' + t + '</span>' : '');
    g.appendChild(s);
  }});
  const map = DATA[w] || {{}};
  for (let wd = 1; wd <= 7; wd++) {{
    for (let r = 2; r <= SLOTS.length + 1; r++) {{
      const bg = document.createElement('div');
      bg.className = 'slot for';
      bg.style.gridColumn = (wd + 1); bg.style.gridRow = r;
      g.appendChild(bg);
    }}
    (map[wd] || []).forEach(e => {{
      const d = document.createElement('div');
      d.className = 'ev';
      d.style.gridColumn = (wd + 1);
      d.style.gridRow = (e.row + 1) + ' / span ' + e.span;
      d.style.margin = '1px';
      d.style.background = e.color + '33';
      d.style.borderLeft = '2.5px solid ' + e.color;
      d.title = e.course + '\\n' + (e.room || '待定') + '\\n' + e.ps + '-' + e.pe + '节 ' + e.t1 + '-' + e.t2;
      const span = e.span > 1 ? ' · 跨' + e.span + '节' : '';
      d.innerHTML = '<b>' + e.course + '</b>' +
        '<span>' + (e.room || '待定') + '</span>' +
        '<span class="t">' + e.ps + '-' + e.pe + '节 ' + e.t1 + '-' + e.t2 + span + '</span>';
      g.appendChild(d);
    }});
  }}
  document.querySelectorAll('.wk').forEach(el =>
    el.classList.toggle('on', +el.dataset.w === w));
  const n = Object.values(map).flat().length;
  document.getElementById('foot').textContent = '第 ' + w + ' 周 · ' + n + ' 节课';
}}
const wh = document.getElementById('weeks');
{json.dumps(weeks)}.forEach(w => {{
  const b = document.createElement('div');
  b.className = 'wk'; b.dataset.w = w; b.textContent = '第 ' + w + ' 周';
  b.onclick = () => {{ cur = w; draw(w); }};
  wh.appendChild(b);
}});
draw(cur);
</script></body></html>"""


def main() -> None:
    data = json.loads((DATA / f"raw_calendar_{CODE}.json").read_text(encoding="utf-8"))["data"]
    units = build_units(data)
    weeks = sorted({u["week"] for u in units})
    html = render(units, weeks)
    out = ROOT / "课表预览.html"
    out.write_text(html, encoding="utf-8")
    print(f"已生成 {out}（{len(units)} 单元，{len(weeks)} 周）")


if __name__ == "__main__":
    main()
