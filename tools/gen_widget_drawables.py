#!/usr/bin/env python3
"""生成桌面部件的课程色 drawable。

为什么用脚本而不是手写 89 个 XML：色值只有 8 组、圆角只有 3 档，手写一定会有
一处写错（比如 widget_kb3 引用了 widget_k3_now），而这类错在真机上只表现为
「某门课颜色怪怪的」，几乎不可能靠人眼在 7 个部件里查出来。脚本生成后可以
随时重跑，产物和设计表一一对应。

产物（全部只引用 @color，深色由 values-night 自动切换，**不为深色另做 drawable**）：

  widget_k{a,b,c}{1..8}        课程块，a/b/c 是圆角档（22/16/13dp）
  widget_k{a,b,c}{1..8}_now    正在上的块（同一档圆角，底色换成 26% 变体）
  widget_dot{1..8}             课程色圆点（oval）
  widget_dot_now               主题色圆点（备用）
  widget_wk{1..8}_{single,first,mid,last}
                               周视图连块。靠上下 inset 拼出「同色严丝合缝、
                               异色留 2dp 缝」的效果，见 _week_cell()。

用法：python tools/gen_widget_drawables.py
"""

from pathlib import Path

RES = Path(__file__).resolve().parent.parent / "app/src/main/res/drawable"

# 圆角档位：a 用于 43dp 高的 2x2 卡，b 用于 30~36dp 的 4x2 卡 / 三日块，
# c 用于 22~26dp 的 4x4 行 / 周视图格。胶囊靠 radius 被 clamp 成半高实现。
RADII = {"a": 22, "b": 16, "c": 13}

HEADER = '<?xml version="1.0" encoding="utf-8"?>\n<!-- 由 tools/gen_widget_drawables.py 生成，不要手改 -->\n'


def write(name: str, body: str) -> None:
    (RES / f"{name}.xml").write_text(HEADER + body, encoding="utf-8")


def block(tier: str, n: int, now: bool) -> None:
    color = f"@color/widget_k{n}{'_now' if now else ''}"
    write(
        f"widget_k{tier}{n}{'_now' if now else ''}",
        f'''<shape xmlns:android="http://schemas.android.com/apk/res/android"
    android:shape="rectangle">
    <solid android:color="{color}" />
    <corners android:radius="{RADII[tier]}dp" />
</shape>
''',
    )


def dot(n: int) -> None:
    write(
        f"widget_dot{n}",
        f'''<shape xmlns:android="http://schemas.android.com/apk/res/android"
    android:shape="oval">
    <solid android:color="@color/widget_k{n}_dot" />
</shape>
''',
    )


def week_cell(n: int, role: str) -> None:
    """周视图的一个格子。

    行高 24dp，格子撑满整行（match_parent），所以「缝」只能靠 inset 做：

      single  上下各 1dp inset  → 和上下两门课都留出 1+1=2dp
      first   只有 top inset    → 下面和同门课的续格直接贴合
      last    只有 bottom inset → 上面和同门课的起始格贴合
      mid     无 inset、无圆角  → 上下都贴合，形成一整块

    两个不同课相邻时是 last(下 1dp) + first(上 1dp)，正好 2dp 的视觉缝；
    同一门课相邻时是 first/last 或 mid，没有 inset，严丝合缝。
    """
    color = f"@color/widget_k{n}"

    if role == "mid":
        write(
            f"widget_wk{n}_mid",
            f'''<shape xmlns:android="http://schemas.android.com/apk/res/android"
    android:shape="rectangle">
    <solid android:color="{color}" />
</shape>
''',
        )
        return

    if role == "single":
        item_attr, corners = 'android:top="1dp" android:bottom="1dp"', (
            '<corners android:radius="12dp" />'
        )
    elif role == "first":
        item_attr, corners = 'android:top="1dp"', (
            '<corners\n            android:topLeftRadius="12dp"\n'
            '            android:topRightRadius="12dp"\n'
            '            android:bottomLeftRadius="0dp"\n'
            '            android:bottomRightRadius="0dp" />'
        )
    else:  # last
        item_attr, corners = 'android:bottom="1dp"', (
            '<corners\n            android:topLeftRadius="0dp"\n'
            '            android:topRightRadius="0dp"\n'
            '            android:bottomLeftRadius="12dp"\n'
            '            android:bottomRightRadius="12dp" />'
        )

    write(
        f"widget_wk{n}_{role}",
        f'''<layer-list xmlns:android="http://schemas.android.com/apk/res/android">
    <item {item_attr}>
        <shape android:shape="rectangle">
            <solid android:color="{color}" />
            {corners}
        </shape>
    </item>
</layer-list>
''',
    )


def main() -> None:
    RES.mkdir(parents=True, exist_ok=True)
    count = 0
    for tier in RADII:
        for n in range(1, 9):
            block(tier, n, now=False)
            block(tier, n, now=True)
            count += 2
    for n in range(1, 9):
        dot(n)
        count += 1
    write(
        "widget_dot_now",
        '''<shape xmlns:android="http://schemas.android.com/apk/res/android"
    android:shape="oval">
    <solid android:color="@color/widget_accent" />
</shape>
''',
    )
    count += 1
    for n in range(1, 9):
        for role in ("single", "first", "mid", "last"):
            week_cell(n, role)
            count += 1
    print(f"generated {count} drawables in {RES}")


if __name__ == "__main__":
    main()
