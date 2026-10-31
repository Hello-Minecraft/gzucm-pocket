#!/usr/bin/env python3
"""
用真实数据渲染页面效果图。

没有微信开发者工具，就用最直接的办法：
把 wxml 里的数据结构手写成 HTML，套上同一份 wxss（rpx → px 换算），
用 Edge 无头模式截图。目的不是 100% 还原小程序渲染，
而是检查「布局会不会塌、文字会不会溢出、颜色对不对」这三类问题。

坐标和颜色都是从 wxss 里读的真值，不是另写一份。
"""

import os
import re
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
MP = os.path.dirname(HERE)
OUT = os.path.join(HERE, 'shots')
os.makedirs(OUT, exist_ok=True)

EDGE = r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe"
if not os.path.exists(EDGE):
    EDGE = r"C:\Program Files\Microsoft\Edge\Application\msedge.exe"


def rpx_to_px(css: str, width=390) -> str:
    """rpx 按 750 设计稿换算：1rpx = 屏宽/750 px"""
    ratio = width / 750.0

    def repl(m):
        v = float(m.group(1))
        return f'{v * ratio:.2f}px'

    return re.sub(r'(-?\d+\.?\d*)rpx', repl, css)


def read(p):
    with open(p, encoding='utf-8') as f:
        return f.read()


def base_css():
    """全局样式 + 所有组件样式。

    组件样式必须一起加进来：`.blk` 的 position:absolute 在 course-card.wxss 里，
    漏了它所有课程块会变成块级元素竖着堆在左边缘 —— 看起来像"全都挤在第一列"，
    但那是我这个渲染脚本的问题，不是小程序的问题。
    """
    app = rpx_to_px(read(os.path.join(MP, 'app.wxss')))
    app = app.replace('page {', 'body {')
    app = app.replace('env(safe-area-inset-top)', '20px')
    app = app.replace('env(safe-area-inset-bottom)', '0px')

    # 拼上所有组件样式
    comp_dir = os.path.join(MP, 'components')
    if os.path.isdir(comp_dir):
        for name in sorted(os.listdir(comp_dir)):
            css_path = os.path.join(comp_dir, name, name + '.wxss')
            if os.path.exists(css_path):
                app += '\n/* --- ' + name + ' --- */\n' + rpx_to_px(read(css_path))
    return app


# ── 假数据（照真实教务返回的字段造）──────────────────────

SEMESTER = {'name': '2026-2027 学年 第一学期'}

TODAY = {
    'greeting': '晚上好',
    'dateText': '11 月 1 日 周六',
    'weekText': '第 9 周',
    'focus': {
        'live': False,
        'countdown': '还有 2 小时 40 分钟',
        'session': {
            'name': '中医内科学(2)',
            'startTime': '19:00',
            'endTime': '21:25',
            'room': '综教楼804（智慧课室）',
            'teachers': '陈晓刚,郭书宁,黄蓓',
            'content': '肺系病证·咳嗽',
        },
    },
    'todayList': [
        {'past': True, 'live': False, 'session': {'startTime': '08:30', 'endTime': '10:00',
         'name': '中医儿科学', 'room': '综教楼808（智慧课室）', 'teachers': '许华'}},
        {'past': True, 'live': False, 'session': {'startTime': '10:20', 'endTime': '11:50',
         'name': '五官学', 'room': '新课室楼302', 'teachers': '刘蓬'}},
        {'past': False, 'live': False, 'session': {'startTime': '14:50', 'endTime': '17:10',
         'name': '外科学(2)', 'room': '一附院结直肠外科', 'teachers': '李玉英,张子敬'}},
        {'past': False, 'live': True, 'session': {'startTime': '19:00', 'endTime': '21:25',
         'name': '中医内科学(2)', 'room': '综教楼804（智慧课室）', 'teachers': '陈晓刚,郭书宁,黄蓓'}},
    ],
    'weekStats': {'courses': 9, 'sections': 21},
}

GRADES_DATA = {
    'gpa': '3.62',
    'avgScore': '84.7',
    'passedCredit': '31.5',
    'creditOpen': True,
    'publicOnly': True,
    'creditTotal': '19',
    'creditCount': 9,
    'creditRows': [
        {'label': '科学技术与文明类', 'creditText': '4', 'pct': 100},
        {'label': '人文社科与素养类', 'creditText': '4', 'pct': 100},
        {'label': '医学基础与临床类', 'creditText': '4', 'pct': 100},
        {'label': '美育类', 'creditText': '4', 'pct': 100},
        {'label': '网络课程类', 'creditText': '3', 'pct': 75},
    ],
    'terms': [
        {'term': '2025-2026-2', 'count': 3, 'gpa': '3.72', 'list': [
            {'name': '中医内科学(2)', 'scoreText': '92', 'creditText': '5',
             'category': '专业课', 'classify': '', 'fail': False},
            {'name': '临床预备课(1)', 'scoreText': '87', 'creditText': '2',
             'category': '实践课', 'classify': '', 'fail': False},
            {'name': '中医外科学', 'scoreText': '85', 'creditText': '4',
             'category': '专业课', 'classify': '', 'fail': False},
        ]},
        {'term': '2025-2026-1', 'count': 2, 'gpa': '3.45', 'list': [
            {'name': '方剂学', 'scoreText': '91', 'creditText': '5',
             'category': '专业基础课', 'classify': '', 'fail': False},
            {'name': '医用生物学', 'scoreText': '58', 'creditText': '2',
             'category': '基础课', 'classify': '', 'fail': True},
        ]},
    ],
}


def build_today():
    d = TODAY
    f = d['focus']
    s = f['session']
    rows = ''
    for it in d['todayList']:
        ss = it['session']
        cls = 'course-row'
        if it['past']:
            cls += ' course-past'
        if it['live']:
            cls += ' course-live'
        dot = '<view class="course-dot"></view>' if it['live'] else ''
        sep = '<text class="course-sep">·</text>' if ss['teachers'] else ''
        te = f'<text>{ss["teachers"]}</text>' if ss['teachers'] else ''
        rows += f'''
        <view class="{cls}">
          <view class="course-time">
            <view class="course-time-start">{ss['startTime']}</view>
            <view class="course-time-end">{ss['endTime']}</view>
          </view>
          <view class="grow">
            <view class="course-name ellipsis">{ss['name']}</view>
            <view class="course-meta ellipsis">{ss['room']}{sep}{te}</view>
          </view>
          {dot}
        </view>'''

    st = d['weekStats']
    return f'''
<view class="page pad safe-top">
  <view class="head">
    <view class="grow">
      <view class="head-title">{d['greeting']}</view>
      <view class="head-sub">{d['dateText']}<text> · {d['weekText']}</text></view>
    </view>
    <view class="head-btn">设置</view>
  </view>

  <view class="focus">
    <view class="focus-tag">{'正在上课' if f['live'] else '下一节'}</view>
    <view class="focus-name">{s['name']}</view>
    <view class="focus-meta"><text>{s['startTime']}–{s['endTime']}</text>
      <text class="focus-sep">·</text><text>{s['room']}</text></view>
    <view class="focus-teacher">{s['teachers']}</view>
    <view class="focus-content">{s['content']}</view>
    <view class="focus-count">{f['countdown']}</view>
  </view>

  <view class="sec">
    <view class="sec-head">
      <view class="sec-title">今天的课</view>
      <view class="sec-sub">{len(d['todayList'])} 节</view>
    </view>
    {rows}
  </view>

  <view class="sec">
    <view class="sec-head"><view class="sec-title">本周概览</view></view>
    <view class="stats">
      <view class="stat"><view class="stat-value">{st['courses']}</view><view class="stat-label">门课</view></view>
      <view class="stat"><view class="stat-value">{st['sections']}</view><view class="stat-label">节课</view></view>
      <view class="stat"><view class="stat-value">{len(d['todayList'])}</view><view class="stat-label">今天</view></view>
    </view>
  </view>

  <view class="sec">
    <view class="sec-head"><view class="sec-title">宿舍电量</view></view>
    <view class="note">
      <view class="note-title">小程序里暂时看不了电费</view>
      <view class="note-body">一卡通只提供网页，没有可以直接读的数据接口，而小程序不能像 App 那样嵌网页去抓。要看电费请用安卓版。</view>
    </view>
  </view>
</view>'''


def build_grades():
    d = GRADES_DATA
    credit_rows = ''
    for r in d['creditRows']:
        credit_rows += f'''
        <view class="credit-row">
          <view class="credit-row-head">
            <view class="credit-label ellipsis">{r['label']}</view>
            <view class="credit-num">{r['creditText']}</view>
          </view>
          <view class="track"><view class="fill" style="width:{r['pct']}%"></view></view>
        </view>'''

    terms = ''
    for t in d['terms']:
        gs = ''
        for g in t['list']:
            fail = ' grade-fail' if g['fail'] else ''
            gs += f'''
            <view class="grade-row">
              <view class="grow">
                <view class="grade-name ellipsis">{g['name']}</view>
                <view class="grade-meta ellipsis">
                  <text>{g['category']}</text>
                  <text class="grade-dot">·</text>
                  <text>{g['creditText']} 学分</text>
                </view>
              </view>
              <view class="grade-score{fail}">{g['scoreText']}</view>
            </view>'''
        terms += f'''
        <view class="term-head">
          <view class="term-name">{t['term']}</view>
          <view class="term-sub">{t['count']} 门 · 绩点 {t['gpa']}</view>
        </view>{gs}'''

    return f'''
<view class="page">
  <view class="safe-top pad">
    <view class="head">
      <view class="grow">
        <view class="head-title">成绩</view>
        <view class="head-sub">83 门 · 8 个学期</view>
      </view>
      <view class="head-btn">同步</view>
    </view>
    <view class="tabs">
      <view class="tab tab-on">课程</view>
      <view class="tab">考级</view>
      <view class="tab">体测</view>
    </view>
  </view>

  <view class="stats pad">
    <view class="stat"><view class="stat-label">加权绩点</view><view class="stat-value">{d['gpa']}</view></view>
    <view class="stat"><view class="stat-label">平均分</view><view class="stat-value">{d['avgScore']}</view></view>
    <view class="stat stat-on"><view class="stat-label">已修学分 ▴</view><view class="stat-value">{d['passedCredit']}</view></view>
  </view>

  <view class="credit pad">
    <view class="credit-head">
      <view class="credit-title">公选学分</view>
      <view class="credit-chips">
        <view class="chip">总学分</view>
        <view class="chip chip-on">公选</view>
      </view>
    </view>
    <view class="credit-sub">{d['creditCount']} 门 · 合计 {d['creditTotal']} 学分</view>
    {credit_rows}
    <view class="credit-note">公选学分需求请自行查培养计划</view>
  </view>

  <view class="pad">{terms}</view>
</view>'''


def build_login():
    return '''
<view class="page">
  <view class="safe-top"></view>
  <view class="wrap">
    <view class="brand">
      <view class="brand-mark">口</view>
      <view class="brand-name">广中医口袋</view>
      <view class="brand-sub">微信小程序版</view>
    </view>

    <view class="qr-box">
      <view class="qr-loading">正在生成二维码…</view>
    </view>

    <view class="steps">
      <view class="step"><view class="step-num">1</view>
        <view class="step-text">用<text class="em">另一台手机</text>的微信扫上面的码</view></view>
      <view class="step"><view class="step-num">2</view>
        <view class="step-text">在微信里点「确认登录」</view></view>
    </view>

    <view class="tips">
      <view class="tips-title">为什么要用另一台设备</view>
      <view class="tips-body">学校的扫码登录要跳进微信的网页完成确认，小程序里做不了这一步。所以得用另一台手机扫 —— 就像在电脑上登录微信一样。</view>
    </view>

    <view class="tips tips-warn">
      <view class="tips-title">关于你的账号</view>
      <view class="tips-body">不保存密码，也不在云端保存登录凭据。登录成功后拿到的会话只存在你自己手机的本地存储里，请求时随请求带走，用完即弃。</view>
    </view>

    <view class="actions"><view class="btn btn-ghost">换一张</view></view>
    <view class="foot">不是官方应用 · 个人做的工具</view>
  </view>
</view>'''


def build_settings():
    return '''
<view class="page">
  <view class="safe-top"></view>
  <view class="nav pad"><view class="nav-back">‹ 返回</view></view>
  <view class="pad">
    <view class="head">
      <view class="head-title">设置</view>
      <view class="head-sub">已登录</view>
    </view>

    <view class="group-label">课表</view>
    <view class="card">
      <view class="row">
        <view class="grow"><view class="row-label">默认启动页</view>
          <view class="row-desc">打开小程序先看哪一页</view></view>
        <view class="chips">
          <view class="chip chip-on">今日</view>
          <view class="chip">课表</view>
          <view class="chip">成绩</view>
        </view>
      </view>
      <view class="row">
        <view class="grow"><view class="row-label">重叠课程</view>
          <view class="row-desc">同一时段两门课时，并排显示还是只显示一门</view></view>
        <view class="sw sw-on"></view>
      </view>
      <view class="row">
        <view class="grow"><view class="row-label">课表网格线</view>
          <view class="row-desc">用虚线标出每一节和每一天</view></view>
        <view class="sw"></view>
      </view>
    </view>

    <view class="group-label">数据</view>
    <view class="card">
      <view class="row">
        <view class="grow"><view class="row-label">课表</view>
          <view class="row-desc">233 节课 · 3 分钟前</view></view>
        <view class="btn btn-ghost">同步</view>
      </view>
      <view class="row">
        <view class="grow"><view class="row-label">成绩</view>
          <view class="row-desc">83 门 · 1 小时前</view></view>
        <view class="btn btn-ghost">同步</view>
      </view>
    </view>

    <view class="group-label">网络</view>
    <view class="card">
      <view class="row">
        <view class="grow"><view class="row-label">请求通道</view>
          <view class="row-desc">云函数转发（正式环境）</view></view>
        <view class="sw"></view>
      </view>
      <view class="note">正式环境必须走云函数转发 —— 学校的域名加不进小程序的请求白名单。直连只在开发者工具里勾选「不校验合法域名」时可用。</view>
    </view>
  </view>
</view>'''


def build_schedule():
    """课表：构造 7 列 × 11 行的网格，含重叠的两门课"""
    row_h_px = 108 * (390 / 750.0)
    slots = [1, 2, 3, 4, 6, 7, 8, 9, 12, 13, 14]
    times = ['08:30', '09:20', '10:20', '11:10', '14:00', '14:50', '15:40', '16:30', '19:00', '19:50', '20:40']

    rows = ''
    for i, (u, t) in enumerate(zip(slots, times)):
        rows += f'''<view class="time-cell" style="height:{row_h_px:.1f}px">
          <view class="time-unit">{u}</view><view class="time-start">{t}</view></view>'''

    # 课程：(星期, 起始行, 跨度, 名称, 教室, 教师, 列, 总列数)
    courses = [
        (1, 0, 2, '中医儿科学', '综教楼808', '许华', 0, 1),
        (1, 2, 2, '五官学', '新课室楼302', '刘蓬', 0, 1),
        (1, 4, 2, '外科学(2)', '一附院结直肠外科', '李玉英', 0, 1),
        (2, 0, 2, '五官学', '新课室楼302', '刘蓬', 0, 1),
        (2, 2, 2, '中医儿科学', '综教楼808', '许华', 0, 1),
        (3, 0, 2, '中医骨伤科学', '新课室楼202', '方坚', 0, 1),
        (3, 2, 2, '中医妇科学', '新课室楼102', '罗颂平', 0, 1),
        (4, 0, 2, '外科学(2)', '综教楼808', '张子敬', 0, 1),
        (4, 2, 2, '中医模拟实践课(4)', '岭南医学研究中心', '王教授', 0, 2),
        (4, 2, 2, '医学伦理学', '新课室楼301', '陈老师', 1, 2),  # 重叠
        (5, 0, 2, '中医妇科学', '新课室楼302', '罗颂平', 0, 1),
        (5, 2, 2, '中医骨伤科学', '新课室楼202', '方坚', 0, 1),
        (6, 1, 2, '中医人文与社会实践课', '待定', '', 0, 1),
    ]

    day_w = 100 / 7
    blocks = ''
    for wd, top, span, name, room, teacher, col, cols in courses:
        inner = 100 / cols
        left = (wd - 1) * day_w + (col * inner * day_w) / 100
        width = (inner * day_w) / 100
        h = span * row_h_px - 3
        teacher_html = f'<view class="blk-teacher">{teacher}</view>' if teacher else ''
        badge = f'<view class="blk-badge">{col + 1}/{cols}</view>' if cols > 1 else ''
        blocks += f'''
        <view class="blk" style="left:{left:.3f}%;width:{width:.3f}%;top:{top * row_h_px + 1.5:.1f}px;height:{h:.1f}px;background:#2A3A2C;border-color:#4A5A4A;color:#D4E0D4">
          <view class="blk-name">{name}</view>
          <view class="blk-room">{room}</view>
          {teacher_html}{badge}
        </view>'''

    return f'''
<view class="page" style="display:flex;flex-direction:column;height:100vh;overflow:hidden">
  <view class="safe-top">
    <view class="head pad">
      <view class="grow">
        <view class="head-title">课表</view>
        <view class="head-sub">{SEMESTER['name']}<text class="head-dot">·</text>第 9 周</view>
      </view>
      <view class="head-btn">网格</view>
      <view class="head-btn">设置</view>
    </view>
  </view>

  <view class="week-strip"><view class="week-row">
    {''.join(f'<view class="week-chip{" week-on" if w==9 else ""}">{w}</view>' for w in range(1,20))}
  </view></view>

  <view class="day-head">
    <view class="day-head-time"></view>
    {''.join(f'<view class="day-head-cell{" day-on" if d==6 else ""}"><view class="day-head-name">{n}</view><view class="day-head-date">11/0{d-1}</view></view>' for d,n in zip(range(1,8),'一二三四五六日'))}
  </view>

  <view class="grid-wrap"><view class="grid" style="height:{len(slots)*row_h_px:.0f}px">
    <view class="time-col">{rows}</view>
    <view class="course-area">{blocks}</view>
  </view></view>
</view>'''


PAGES = [
    ('today', build_today, 'pages/today/today.wxss'),
    ('grades', build_grades, 'pages/grades/grades.wxss'),
    ('schedule', build_schedule, 'pages/schedule/schedule.wxss'),
    ('login', build_login, 'pages/login/login.wxss'),
    ('settings', build_settings, 'pages/settings/settings.wxss'),
]


def render(name, html_body, page_css_path, width=390, height=844):
    css = base_css() + '\n' + rpx_to_px(read(os.path.join(MP, page_css_path)), width)
    # 开关控件（真机是小程序原生 switch，这里画一个示意）
    css += '''
    .sw{width:44px;height:26px;border-radius:13px;background:#333C36;position:relative;flex-shrink:0}
    .sw::after{content:'';position:absolute;width:22px;height:22px;border-radius:11px;background:#9AA69C;top:2px;left:2px}
    .sw-on{background:#4E6B4E}
    .sw-on::after{left:auto;right:2px;background:#8FA98F}
    .track{width:100%;height:4px;border-radius:2px;background:rgba(168,180,171,.18);overflow:hidden;margin-top:4px}
    .fill{height:100%;border-radius:2px;background:#8FA98F}
    .chip{display:inline-flex;align-items:center;padding:4px 10px;border-radius:6px;font-size:12px;color:#A8B4AB}
    .chip-on{background:rgba(143,169,143,.16);color:#8FA98F;font-weight:600}
    .btn-ghost{background:transparent;border:1px solid #333C36;color:#A8B4AB;padding:6px 12px;border-radius:6px;font-size:12px}
    '''
    html = f'''<!DOCTYPE html><html><head><meta charset="utf-8">
<style>
* {{ margin:0; padding:0; box-sizing:border-box; }}
body {{ width:{width}px; }}

/*
 * 关键：把小程序标签映射成 block。
 *
 * <view> 是未知元素，浏览器默认 display:inline —— 这样 position:absolute
 * 的定位上下文会错乱（伪元素的 top/bottom 不起作用），课程色条直接消失。
 * 必须显式声明成 block，并且让 .course-row 这类容器建立定位上下文。
 */
view, scroll-view, swiper, block {{ display:block; }}
text {{ display:inline; }}

{css}
</style></head><body>{html_body}</body></html>'''

    html_path = os.path.join(OUT, f'{name}.html')
    png_path = os.path.join(OUT, f'{name}.png')
    with open(html_path, 'w', encoding='utf-8') as f:
        f.write(html)

    cmd = [
        EDGE,
        '--headless=new',
        '--disable-gpu',
        '--hide-scrollbars',
        f'--window-size={width},{height}',
        f'--screenshot={png_path}',
        f'file:///{html_path.replace(os.sep, "/")}',
    ]
    r = subprocess.run(cmd, capture_output=True, text=True, timeout=60)
    ok = os.path.exists(png_path) and os.path.getsize(png_path) > 2000
    print(f'  {"✓" if ok else "✗"} {name}.png  {os.path.getsize(png_path) if ok else 0} B')
    return ok


def main():
    print('渲染页面效果图（390x844，iPhone 14 尺寸）\n')
    allok = True
    for name, builder, css in PAGES:
        try:
            allok &= render(name, builder(), css)
        except Exception as e:
            print(f'  ✗ {name}: {e}')
            allok = False
    print(f'\n产物在 {OUT}')
    return 0 if allok else 1


if __name__ == '__main__':
    sys.exit(main())