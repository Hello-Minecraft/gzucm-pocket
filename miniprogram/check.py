#!/usr/bin/env python3
"""
小程序静态一致性检查。

tsc 只管 TS 之间的一致性，管不了 wxml 里写的字段名和 bindtap 方法名。
这类错误在小程序里表现为：
  · 字段名写错 → 界面空白，控制台不报错
  · 方法名写错 → 点了没反应
这两种都很难一眼看出来，所以用脚本先扫一遍。

检查项：
  1. wxml 里 {{xxx}} / wx:if="{{xxx}}" 用到的顶层字段，在 ts 的 data 里存在
  2. wxml 里 bindtap / bindchange / bindinput 的方法名，在 ts 里存在
  3. custom component 的 properties 与调用方传的属性对得上
  4. json 里 usingComponents 指向的组件文件确实存在
"""

import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.abspath(__file__))

# 小程序内置 / 内置命名空间，不算自定义字段
BUILTIN = {
    'true', 'false', 'null', 'undefined', 'item', 'index', 'true', 'false',
}


def read(p):
    with open(p, encoding='utf-8') as f:
        return f.read()


def strip_template_expr(expr):
    """从 {{...}} 里抠出可能的顶层标识符"""
    out = []
    # 去掉字符串字面量
    expr = re.sub(r"'[^']*'", "''", expr)
    expr = re.sub(r'"[^"]*"', '""', expr)
    # 对象字面量的 key 不要
    expr = re.sub(r'(\w+)\s*:', '', expr)
    for m in re.finditer(r'[A-Za-z_$][\w$]*', expr):
        name = m.group(0)
        if name in BUILTIN:
            continue
        # 点号后面的是属性，不是顶层变量
        start = m.start()
        if start > 0 and expr[start - 1] == '.':
            continue
        out.append(name)
    return out


def collect_data_fields(ts_src):
    """从 Page/Component 的 data: {...} 里取顶层 key"""
    fields = set()
    # 先定位 data: {
    m = re.search(r'\bdata\s*:\s*\{', ts_src)
    if not m:
        return fields
    i = m.end() - 1  # 指向 '{'
    depth = 0
    j = i
    while j < len(ts_src):
        c = ts_src[j]
        if c == '{':
            depth += 1
        elif c == '}':
            depth -= 1
            if depth == 0:
                break
        j += 1
    block = ts_src[i + 1:j]
    # 只取第 0 层的 key
    depth = 0
    k = 0
    buf = ''
    while k < len(block):
        c = block[k]
        if c in '{[(':
            depth += 1
        elif c in '}])':
            depth -= 1
        if depth == 0 and c == ':':
            mm = re.search(r'([A-Za-z_$][\w$]*)\s*$', buf)
            if mm:
                fields.add(mm.group(1))
            buf = ''
            k += 1
            # 跳过值直到同层逗号
            continue
        if depth == 0 and c == ',':
            buf = ''
            k += 1
            continue
        buf += c
        k += 1
    return fields


def collect_methods(ts_src):
    """Page 的方法挂在顶层，Component 的在 methods 里"""
    names = set()
    # 顶层 methodName(...) { 或 methodName: function
    for m in re.finditer(r'^\s{2}([A-Za-z_$][\w$]*)\s*\(', ts_src, re.M):
        names.add(m.group(1))
    # methods: { ... } 里的
    mm = re.search(r'\bmethods\s*:\s*\{', ts_src)
    if mm:
        i = mm.end() - 1
        depth = 0
        j = i
        while j < len(ts_src):
            if ts_src[j] == '{':
                depth += 1
            elif ts_src[j] == '}':
                depth -= 1
                if depth == 0:
                    break
            j += 1
        block = ts_src[i + 1:j]
        for m2 in re.finditer(r'([A-Za-z_$][\w$]*)\s*\(', block):
            names.add(m2.group(1))
        for m2 in re.finditer(r'([A-Za-z_$][\w$]*)\s*:\s*function', block):
            names.add(m2.group(1))
    # observers 里的（Component）
    mo = re.search(r'\bobservers\s*:\s*\{', ts_src)
    if mo:
        i = mo.end() - 1
        depth = 0
        j = i
        while j < len(ts_src):
            if ts_src[j] == '{':
                depth += 1
            elif ts_src[j] == '}':
                depth -= 1
                if depth == 0:
                    break
            j += 1
        for m2 in re.finditer(r'^\s*[\'"]([^\'"]+)[\'"]\s*:', ts_src[i + 1:j], re.M):
            pass  # observers 的 key 是字段名，不是方法
    return names


def collect_properties(ts_src):
    """Component 的 properties"""
    props = set()
    m = re.search(r'\bproperties\s*:\s*\{', ts_src)
    if not m:
        return props
    i = m.end() - 1
    depth = 0
    j = i
    while j < len(ts_src):
        if ts_src[j] == '{':
            depth += 1
        elif ts_src[j] == '}':
            depth -= 1
            if depth == 0:
                break
        j += 1
    for m2 in re.finditer(r'^\s{4}([A-Za-z_$][\w$]*)\s*:', ts_src[i + 1:j], re.M):
        props.add(m2.group(1))
    return props


problems = []
checked = 0


def check_unit(json_path):
    global checked
    base = os.path.splitext(json_path)[0]
    wxml_path = base + '.wxml'
    ts_path = base + '.ts'
    if not os.path.exists(wxml_path) or not os.path.exists(ts_path):
        return
    checked += 1

    wxml = read(wxml_path)
    ts = read(ts_path)
    cfg = json.loads(read(json_path))

    data_fields = collect_data_fields(ts)
    methods = collect_methods(ts)
    props = collect_properties(ts)
    is_component = 'component' in cfg and cfg['component'] is True

    # ① 模板表达式里的字段
    refs = set()
    for m in re.finditer(r'\{\{(.*?)\}\}', wxml, re.S):
        refs.update(strip_template_expr(m.group(1)))

    # wx:for 会引入 item/index，wx:for-item 可自定义
    for m in re.finditer(r'wx:for-item\s*=\s*"([^"]+)"', wxml):
        refs.discard(m.group(1))
    for m in re.finditer(r'wx:for-index\s*=\s*"([^"]+)"', wxml):
        refs.discard(m.group(1))

    known = data_fields | props if is_component else data_fields
    # wxml 里也可能调用 methods（如 {{fmt(x)}}），不算字段
    unknown = refs - known - methods
    for u in sorted(unknown):
        problems.append(f'{os.path.relpath(wxml_path, ROOT)}: 字段 {u!r} 在 ts 里没有声明')

    # ② 事件方法
    for m in re.finditer(r'bind:?(\w+)\s*=\s*"([A-Za-z_$][\w$]*)"', wxml):
        fn = m.group(2)
        if fn not in methods:
            problems.append(f'{os.path.relpath(wxml_path, ROOT)}: 事件方法 {fn!r} 在 ts 里不存在')

    # ③ 组件属性
    for name, path in (cfg.get('usingComponents') or {}).items():
        target = os.path.join(ROOT, path.lstrip('/'))
        comp_json = target + '.json'
        comp_ts = target + '.ts'
        if not os.path.exists(comp_json):
            problems.append(f'{os.path.relpath(json_path, ROOT)}: 组件 {name} 路径不存在 {path}')
            continue
        cprops = collect_properties(read(comp_ts)) if os.path.exists(comp_ts) else set()
        # 找 wxml 里这个标签实际传了哪些属性
        for tag in re.finditer(r'<' + re.escape(name) + r'\b([^>]*?)/?>', wxml, re.S):
            attr_text = tag.group(1)
            for am in re.finditer(r'([a-zA-Z][\w:-]*)\s*=\s*"', attr_text):
                attr = am.group(1)
                if attr.startswith('bind') or attr.startswith('catch'):
                    continue
                if attr in ('class', 'style', 'id', 'wx:if', 'wx:else', 'wx:for', 'wx:key',
                            'data-', 'slot'):
                    continue
                if attr.startswith('data-') or attr.startswith('wx:'):
                    continue
                if attr not in cprops:
                    problems.append(
                        f'{os.path.relpath(wxml_path, ROOT)}: <{name} {attr}=...> 组件没声明这个属性'
                    )


for dirpath, _, files in os.walk(ROOT):
    if 'node_modules' in dirpath or 'cloudfunctions' in dirpath:
        continue
    for f in files:
        if f.endswith('.json'):
            p = os.path.join(dirpath, f)
            try:
                cfg = json.loads(read(p))
            except Exception:
                problems.append(f'{os.path.relpath(p, ROOT)}: JSON 解析失败')
                continue
            if isinstance(cfg, dict) and ('usingComponents' in cfg or 'component' in cfg
                                          or 'pages' in cfg):
                check_unit(p)

print(f'检查了 {checked} 个页面/组件')
if problems:
    print(f'\n发现 {len(problems)} 个问题：')
    for p in problems:
        print('  ✗', p)
    sys.exit(1)
else:
    print('全部通过 ✓')
    sys.exit(0)