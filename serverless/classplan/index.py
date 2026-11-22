# -*- coding: utf-8 -*-
"""
班级日程云端版（阿里云函数计算 FC 3.0，Python 3.10，HTTP 触发器）

## 这个函数是什么

`docs/CLASSPLAN_CLOUD.md` 那份契约的服务端实现。学委在 App 里维护一份清单，
发到这个函数；同班同学打开 App 时按班级名拉一份索引，认领自己要的那份。
它只做三件事，多一件都不做：

    · 存 / 取一份清单（publish / get）
    · 给全班列一份「有哪些清单」的摘要（index）
    · 让本人删掉自己发的（delete）

配额和归属校验都在这儿，客户端说什么都只当「线索」，当不了证据 ——
`uploader` 是学号，客户端自己填的，服务端验不了真伪（教务是 cookie 会话，
FC 拿不到可信凭证）。所以安全模型是「可追溯 + 有配额」，不是严格认证。
这一点契约第 3 节写得很清楚，代码里不再假装。

## 为什么是 OSS 而不是数据库

一份清单几 KB，一个班封顶 8 份，全班一天看一两次 —— 这个量级开个数据库
纯属给自己找运维。代价是**没有事务**：正文对象和 `_index.json` 可能短暂不一致。
所以索引被明确设计成**可丢弃缓存**，丢了就 ListObjects + 逐个 HEAD 重建。
读索引失败、JSON 解析失败、字段缺胳膊少腿，一律当「没有索引」，不能因此让接口挂掉。

## 部署

见同目录 `README.md`。要点：入口 `index.handler`，五个环境变量缺一不可
（本文件 import 时就会检查，缺哪个打哪个，然后启动失败 —— 宁可不启动，
也别带着半个配置上线，那种错要等同学报「打不开」才会被发现）。

## 已知边界

- 限流是**进程内**的（跟 qweather-proxy 一样）：FC 会扩容、会回收实例，
  它挡不住分布式刷。目的只是别让一个班同时打开时把 OSS 的 QPS 打满。
- 索引重建是 O(N) 个 HEAD（N ≤ 8），只在缓存坏掉时发生，不做定时刷新。
- 配额在并发发布时可能被短暂突破（先读索引再写）。真实场景是一个班一个学委，
  不值得为此上锁。
"""

import base64
import hashlib
import hmac
import json
import os
import re
import time
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
from email.utils import formatdate

# ── 配置：缺一个就启动失败 ────────────────────────────────────────
#
# 故意在 import 阶段检查，不留「运行时再说」的余地：
# FC 上 import 失败是一个明确的启动错误，日志第一行就能看到缺什么；
# 而运行时才报错会变成每个请求 500，还混在访问日志里。

_REQUIRED = ("OSS_AK", "OSS_SK", "OSS_BUCKET", "OSS_ENDPOINT", "APP_TOKEN")
_missing = [name for name in _REQUIRED if not os.environ.get(name, "").strip()]
if _missing:
    for _name in _missing:
        print(f"[classplan] 缺少环境变量 {_name}")
    raise RuntimeError("缺少环境变量: " + ", ".join(_missing))

OSS_AK = os.environ["OSS_AK"].strip()
OSS_SK = os.environ["OSS_SK"].strip()
OSS_BUCKET = os.environ["OSS_BUCKET"].strip()
# 允许填 oss-cn-guangzhou.aliyuncs.com 或带 https:// 的完整地址，统一成后者
OSS_ENDPOINT = os.environ["OSS_ENDPOINT"].strip().rstrip("/")
if not OSS_ENDPOINT.startswith(("http://", "https://")):
    OSS_ENDPOINT = "https://" + OSS_ENDPOINT
APP_TOKEN = os.environ["APP_TOKEN"].strip()
RATE_LIMIT_PER_MIN = int(os.environ.get("RATE_LIMIT_PER_MIN", "60"))

# ── 契约里的常量 ──────────────────────────────────────────────────

ROOT = "classplan/v1"
INDEX_NAME = "_index.json"
MAX_CLASS_LEN = 40          # 契约 2.3 #2
MAX_KEY_LEN = 12            # 契约 2.3 #3
MIN_TASKS, MAX_TASKS = 1, 80  # 契约 2.3 #4（80 条 = 一学期 15 门课各 5 次作业）
QUOTA_USER = 2              # 契约 2.3 #5
QUOTA_CLASS = 8             # 契约 2.3 #6

# 正文上限。契约只限了条数，没限单条长度 —— 一条 10MB 的 title 也能过校验。
# 这不是防同学（有 APP_TOKEN 和限流兜着），是防一次手滑把桶写爆。
MAX_BODY_BYTES = 256 * 1024

# planId 会直接拼进对象 key，**必须**先校验形状：
# 否则 id = "../../../x" 就能让 get / delete 打到别的 key 上。
_PLAN_ID_RE = re.compile(r"^[0-9a-f]{6}$")

_RATE = {}   # ip -> [window_start, count]


# ── 契约里的两个哈希 ──────────────────────────────────────────────

def class_hash(class_name: str) -> str:
    """sha256(班级名 UTF-8)[:16]。班级名**不做任何规范化**，两端必须逐字节一致。"""
    return hashlib.sha256(class_name.encode("utf-8")).hexdigest()[:16]


def plan_id(key: str) -> str:
    """sha256(学委名字)[:6]。同一个名字永远落到同一个对象上 —— 这就是「更新」的实现。"""
    return hashlib.sha256(key.encode("utf-8")).hexdigest()[:6]


# ── OSS V1 签名 ───────────────────────────────────────────────────
#
# 签名原串是五行固定顺序，少一行、多一个空格都会 SignatureDoesNotMatch，
# 而 OSS 的报错只说「签名不对」，不说差在哪。所以这里把每一行的来源都标出来：
#
#     VERB\n
#     Content-MD5\n          ← 我们不用，空着（不是 "null"，就是一个空字符串）
#     Content-Type\n         ← 必须和真正发出去的 Content-Type 一字不差
#     Date\n                 ← 必须是 GMT 格式（formatdate(usegmt=True)）
#     CanonicalizedOSSHeaders + CanonicalizedResource
#
# CanonicalizedOSSHeaders 是**所有** x-oss-* 头，小写、按名字排序、每行 `name:value\n`。
# 我们的自定义元数据 x-oss-meta-* 也在这个范围里 —— 这是最容易漏的一处：
# 头发出去了却没参与签名，PUT 一律 403。

def _canonical_oss_headers(headers: dict) -> str:
    items = sorted((k.lower(), str(v)) for k, v in headers.items()
                   if k.lower().startswith("x-oss-"))
    return "".join(f"{k}:{v}\n" for k, v in items)


def _sign(method: str, resource: str, content_type: str,
          date: str, extra_headers: dict) -> str:
    to_sign = "\n".join([
        method,
        "",                       # Content-MD5：不用
        content_type,
        date,
        _canonical_oss_headers(extra_headers) + resource,
    ])
    digest = hmac.new(OSS_SK.encode("utf-8"), to_sign.encode("utf-8"),
                      hashlib.sha1).digest()
    return f"OSS {OSS_AK}:{base64.b64encode(digest).decode('ascii')}"


def _base_url() -> str:
    """算出访问根地址，**bucket 必须进域名**。

    2024 年之后新建的桶默认关闭 path-style（`oss-cn-xxx.aliyuncs.com/bucket/key`），
    用它访问会得到 `SecondLevelDomainForbidden`：「must be addressed using
    OSS third level domain」。所以拼成 `https://{bucket}.oss-cn-xxx.aliyuncs.com/`。
    签名里的 CanonicalizedResource 仍然要带 bucket（`/{bucket}/{key}`），
    那是规范，不随访问域名变 —— 这两件事很容易被混成一件事。
    """
    parsed = urllib.parse.urlsplit(OSS_ENDPOINT)
    host = parsed.netloc
    if not host.startswith(f"{OSS_BUCKET}.") and "aliyuncs.com" in host:
        host = f"{OSS_BUCKET}.{host}"
        return f"{parsed.scheme}://{host}/"
    if host.startswith(f"{OSS_BUCKET}."):
        return f"{parsed.scheme}://{host}/"
    # 自定义域名 / 内网 IP：退回 path-style（这类端点通常允许）
    return f"{OSS_ENDPOINT}/{OSS_BUCKET}/"


_BASE_URL = _base_url()


def oss(method: str, key: str = None, query: dict = None,
        body: bytes = None, content_type: str = "", meta: dict = None):
    """发一个 OSS 请求，返回 `(status, headers, body_bytes)`。

    **不抛 4xx/5xx**：404（对象不存在）是业务的一部分，调用方要能区分它和
    「网络挂了」。网络层错误统一返回 status=0 —— 让调用方当 502 处理。

    `key=None` 表示桶级操作（ListObjects）。注意它的 CanonicalizedResource
    是 `/{bucket}/`，**不含 `?prefix=`** —— prefix/marker/max-keys 在 V1 里
    是普通查询参数，不进签名。把它们拼进 resource 是最常见的 403 来源之一。
    """
    resource = f"/{OSS_BUCKET}/" + (key or "")
    url = _BASE_URL
    if key:
        # key 只含 ASCII（hex/固定名字），保留 / 作为层级分隔
        url += urllib.parse.quote(key, safe="/")
    if query:
        url += "?" + urllib.parse.urlencode(query, safe="/")

    extra = dict(meta or {})
    send_headers = dict(extra)
    if content_type:
        send_headers["Content-Type"] = content_type

    date = formatdate(usegmt=True)
    send_headers["Date"] = date
    send_headers["Authorization"] = _sign(method, resource, content_type, date, extra)

    req = urllib.request.Request(url, data=body, headers=send_headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            return resp.status, dict(resp.headers), resp.read()
    except urllib.error.HTTPError as e:
        # 404 / 403 / 409 都在这里：body 是 OSS 的 XML，日志里留 300 字节够定位
        try:
            raw = e.read()
        except Exception:
            raw = b""
        print(f"[classplan] OSS {method} {resource} -> {e.code}: "
              f"{raw[:300].decode('utf-8', 'replace')}")
        return e.code, dict(e.headers or {}), raw
    except Exception as e:
        print(f"[classplan] OSS {method} {resource} 失败: {type(e).__name__}: {e}")
        return 0, {}, str(e).encode("utf-8", "replace")


def list_keys(prefix: str) -> list:
    """列出 prefix 下的所有 key（分页走完）。

    一页 1000 个，我们一个班最多 9 个对象，理论上一次就够；
    留着循环是为了哪天有人手动塞了一堆测试对象时不会「只重建一半」。
    """
    keys, marker = [], None
    for _ in range(10):
        q = {"prefix": prefix, "max-keys": "1000"}
        if marker:
            q["marker"] = marker
        status, _, raw = oss("GET", None, query=q)
        if status != 200:
            raise RuntimeError(f"ListObjects {prefix} 失败（status={status}）")
        root = ET.fromstring(raw)
        truncated, next_marker = False, None
        for el in root.iter():
            tag = el.tag.rsplit("}", 1)[-1]        # 去掉 XML 命名空间
            if tag == "Contents":
                for child in el:
                    if child.tag.rsplit("}", 1)[-1] == "Key":
                        keys.append(child.text or "")
            elif tag == "IsTruncated":
                truncated = (el.text or "").strip().lower() == "true"
            elif tag == "NextMarker":
                next_marker = (el.text or "").strip() or None
        if not truncated or not next_marker:
            break
        marker = next_marker
    return keys


# ── 索引：读、建、写 ──────────────────────────────────────────────

def index_key(ch: str) -> str:
    return f"{ROOT}/{ch}/{INDEX_NAME}"


def plan_key(ch: str, pid: str) -> str:
    return f"{ROOT}/{ch}/{pid}.json"


def _as_int(value, default: int = 0) -> int:
    try:
        return int(value)
    except (TypeError, ValueError):
        return default


def read_index(ch: str):
    """读 `_index.json`。任何问题（不存在 / 解析失败 / 结构不对）都返回 None，
    交给调用方重建 —— 缓存坏了不是错误，别让同学看到 500。"""
    status, _, raw = oss("GET", index_key(ch))
    if status == 404:
        return None
    if status != 200:
        return None
    try:
        data = json.loads(raw.decode("utf-8"))
    except Exception as e:
        print(f"[classplan] _index.json 解析失败，将重建: {type(e).__name__}: {e}")
        return None
    if not isinstance(data, dict) or not isinstance(data.get("plans"), list):
        print("[classplan] _index.json 结构不对，将重建")
        return None
    return data


def write_index(ch: str, plans: list, updated_at: int) -> bool:
    """回写索引。失败只打日志不抛：索引是缓存，写坏了下次读照样能重建，
    不能因为「缓存没写成」把一次成功的发布变成 500。"""
    plans = sorted(plans, key=lambda p: (-_as_int(p.get("updatedAt")), p.get("id", "")))
    body = json.dumps({"v": 1, "updatedAt": updated_at, "plans": plans},
                      ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    status, _, raw = oss("PUT", index_key(ch), body=body,
                         content_type="application/json",
                         # 索引里全是中文 key，元数据只做标记，不做数据源
                         meta={"x-oss-meta-kind": "index"})
    if status not in (200, 201):
        print(f"[classplan] 回写 _index.json 失败（status={status}），下次读会重建")
        return False
    return True


def _brief_from_head(key: str):
    """从一个正文对象还原出索引摘要。

    优先信对象元数据（一次 HEAD，不用拉正文）；元数据缺字段时退回读正文。
    元数据这一层不是「额外优化」而是重建的正确性来源：班级最多 8 份，
    每次重建都 GET 一遍正文的话，缓存一坏就是 8 次几 KB 的下载。
    """
    status, headers, _ = oss("HEAD", key)
    if status != 200:
        return None

    h = {k.lower(): v for k, v in headers.items()}
    name = key.rsplit("/", 1)[-1]
    pid = name[:-5] if name.endswith(".json") else name
    if not _PLAN_ID_RE.match(pid):
        return None

    # 学委名字是中文，HTTP 头只能放 ASCII —— 写的时候 quote 过，这里解回来
    key_name = urllib.parse.unquote(h.get("x-oss-meta-key", "") or "")
    maintainer = urllib.parse.unquote(h.get("x-oss-meta-maintainer", "") or "")
    # 姓名（展示用）。老对象没有这个 meta —— 留空即可，不要为了它多打一次 GET：
    # 重建一次最多 8 份，每份都补一次正文下载会把扩容时的冷启动拖长。
    owner_name = urllib.parse.unquote(h.get("x-oss-meta-owner-name", "") or "")
    # 清单名（2026-11-23 加）：学委起的那摊事的名字，如「期末作业清单」。
    # 老对象没有这个 meta，留空让客户端兜底。
    plan_name = urllib.parse.unquote(h.get("x-oss-meta-name", "") or "")
    count = _as_int(h.get("x-oss-meta-count"), -1)
    updated = _as_int(h.get("x-oss-meta-updatedat"), 0)

    # 缺 name 也读一次正文：老对象（这个字段加之前发的）meta 里没有 name，
    # 不回填的话学委重新发布之前，同学那边永远看不到清单名。
    # 一个班最多 8 份，重建时多读几次正文不算什么。
    if count < 0 or not key_name or not plan_name:
        # 元数据不全（别的东西写进来的对象，或早期版本），读正文兜底。
        # 一处失败不影响整次重建，返回 None 让调用方跳过这一条。
        status, _, raw = oss("GET", key)
        if status != 200:
            return None
        try:
            plan = json.loads(raw.decode("utf-8"))
        except Exception:
            return None
        if not isinstance(plan, dict):
            return None
        key_name = key_name or str(plan.get("key") or "")
        maintainer = maintainer or str(plan.get("maintainer") or "")
        owner_name = owner_name or str(plan.get("maintainer") or "")
        plan_name = plan_name or str(plan.get("name") or "")
        tasks = plan.get("tasks")
        count = len(tasks) if isinstance(tasks, list) else 0
        updated = updated or _as_int(plan.get("updatedAt"), 0)

    return {"id": pid, "key": key_name, "maintainer": maintainer,
            "ownerName": owner_name, "name": plan_name,
            "count": max(count, 0), "updatedAt": updated}


def rebuild_index(ch: str) -> dict:
    """ListObjects + 逐个 HEAD 重建，并回写。

    单条对象坏了只跳过那一条 —— 重建本身是「救火」，不能因为一个脏对象
    把整班同学都挡在外面。真正让重建失败的是 ListObjects 本身不通，
    那属于上游故障，往上抛。
    """
    prefix = f"{ROOT}/{ch}/"
    plans = []
    for key in list_keys(prefix):
        if key.endswith("/") or key.rsplit("/", 1)[-1] == INDEX_NAME:
            continue
        if not key.endswith(".json"):
            continue
        try:
            brief = _brief_from_head(key)
        except Exception as e:
            print(f"[classplan] 重建时跳过 {key}: {type(e).__name__}: {e}")
            continue
        if brief:
            plans.append(brief)

    updated = max([p["updatedAt"] for p in plans] or [0])
    print(f"[classplan] 重建 {ch} 的索引：{len(plans)} 份")
    # 空班不落盘：有人拿随机班级名扫接口时，别给每个名字都留一个空索引对象。
    # 反正第一次 publish 会写，之后读到的就是真索引。
    if plans:
        write_index(ch, plans, updated)
    return {"v": 1, "updatedAt": updated, "plans": plans}


def load_index(ch: str) -> dict:
    """取索引：缓存可用就用缓存，否则重建。"""
    idx = read_index(ch)
    return idx if idx is not None else rebuild_index(ch)


# ── 响应 ──────────────────────────────────────────────────────────

def _resp(status: int, payload: dict):
    return {
        "statusCode": status,
        "headers": {
            "Content-Type": "application/json;charset=utf-8",
            # 清单一天可能变几次，中间层缓存住就会让学委以为「发了没生效」
            "Cache-Control": "no-store",
        },
        "body": json.dumps(payload, ensure_ascii=False),
    }


def ok(**fields):
    payload = {"ok": True}
    payload.update(fields)
    return _resp(200, payload)


def fail(status: int, code: str, message: str):
    """错误一律这个形状：`error` 给 App 判断，`message` 给人看。

    中文 message 是契约要求（「中文人话」），App 那边原样显示，
    所以别写「internal error」这种，也别把 OSS 的 XML 吐出去。
    """
    return _resp(status, {"ok": False, "error": code, "message": message})


# ── 入参解析 ──────────────────────────────────────────────────────

def parse_event(event):
    """把 FC 事件函数的事件摊平成我们要的几个字段。

    FC 3.0 HTTP 触发器给的是 `rawPath` / `headers` / `body` /
    `isBase64Encoded`，方法在 `requestContext.http.method`。
    这里把 body 和 query 合成一个 dict：契约说「GET 也能走」，
    那 GET 时参数就在 query 里，合并之后业务代码只认一个 payload。
    """
    if isinstance(event, (bytes, bytearray)):
        event = json.loads(event.decode("utf-8"))
    if isinstance(event, str):
        event = json.loads(event)
    if not isinstance(event, dict):
        event = {}

    headers = {str(k).lower(): v for k, v in (event.get("headers") or {}).items()}

    raw = event.get("body") or ""
    if event.get("isBase64Encoded") and raw:
        try:
            raw = base64.b64decode(raw).decode("utf-8", "replace")
        except Exception:
            raw = ""
    if isinstance(raw, (bytes, bytearray)):
        raw = raw.decode("utf-8", "replace")

    payload = {}
    if raw.strip():
        try:
            parsed = json.loads(raw)
            if isinstance(parsed, dict):
                payload = parsed
        except Exception:
            # body 不是 JSON 时不要 500：当成空 payload，
            # 后面的字段校验会自然给出 bad_class / bad_key
            print(f"[classplan] body 不是 JSON（{len(raw)} 字节），按空处理")

    for k, v in (event.get("queryParameters") or {}).items():
        payload.setdefault(k, v)

    rc = event.get("requestContext") or {}
    http = rc.get("http") or {}
    path = event.get("rawPath") or event.get("path") or http.get("path") or "/"
    ip = (http.get("sourceIp")
          or headers.get("x-forwarded-for", "").split(",")[0].strip()
          or "unknown")

    return {
        "path": path,
        "headers": headers,
        "payload": payload,
        "raw_len": len(raw.encode("utf-8")) if isinstance(raw, str) else len(raw),
        "ip": ip,
    }


def rate_ok(ip: str) -> bool:
    """每实例每分钟 N 次。跟 qweather-proxy 一个写法，边界也一样（见模块注释）。"""
    now = int(time.time())
    slot = _RATE.get(ip)
    if not slot or now - slot[0] >= 60:
        _RATE[ip] = [now, 1]
        return True
    if slot[1] >= RATE_LIMIT_PER_MIN:
        return False
    slot[1] += 1
    return True


def _class_of(payload: dict):
    """校验班级名，返回 `(class_name, None)` 或 `(None, 错误响应)`。"""
    name = payload.get("class")
    if not isinstance(name, str) or not name.strip():
        return None, fail(400, "bad_class", "缺少班级名")
    if len(name) > MAX_CLASS_LEN:
        return None, fail(400, "bad_class", f"班级名最长 {MAX_CLASS_LEN} 个字")
    return name, None


# ── 四个端点 ──────────────────────────────────────────────────────

def h_index(payload: dict):
    """POST /classplan/index —— 全班清单摘要。"""
    name, err = _class_of(payload)
    if err:
        return err
    try:
        idx = load_index(class_hash(name))
    except Exception as e:
        print(f"[classplan] 取索引失败: {type(e).__name__}: {e}")
        return fail(502, "server_error", "清单列表暂时读不到，请稍后重试")

    # 只回契约里列出的字段。索引里若以后加了内部字段，不能顺手漏出去。
    # 加字段时**记得同步这里** —— 漏掉的话前端拿到的是 None，
    # 表现成「字段明明写了却不显示」，很难查（2026-11-22 就踩了一次）。
    plans = [{
        "id": str(p.get("id") or ""),
        "key": str(p.get("key") or ""),
        "maintainer": str(p.get("maintainer") or ""),
        "ownerName": str(p.get("ownerName") or ""),
        "name": str(p.get("name") or ""),
        "count": _as_int(p.get("count")),
        "updatedAt": _as_int(p.get("updatedAt")),
    } for p in idx.get("plans") or []]

    return ok(updatedAt=_as_int(idx.get("updatedAt")), plans=plans)


def h_get(payload: dict):
    """POST /classplan/get —— 正文原样返回，一个字都不改。"""
    name, err = _class_of(payload)
    if err:
        return err
    pid = str(payload.get("id") or "").strip()
    if not _PLAN_ID_RE.match(pid):
        # 形状不对就是「没有这份」，不额外发明错误码
        return fail(404, "not_found", "这份清单不存在，可能已经被删掉了")

    status, _, raw = oss("GET", plan_key(class_hash(name), pid))
    if status == 404:
        return fail(404, "not_found", "这份清单不存在，可能已经被删掉了")
    if status != 200:
        return fail(502, "server_error", "清单暂时读不到，请稍后重试")

    try:
        plan = json.loads(raw.decode("utf-8"))
    except Exception as e:
        print(f"[classplan] 正文解析失败 {pid}: {type(e).__name__}: {e}")
        return fail(502, "server_error", "清单内容损坏，请联系发布者重新上传")

    return ok(plan=plan)


def h_publish(payload: dict):
    """POST /classplan/publish —— 校验顺序严格照契约 2.3 的表。

    顺序不是随手排的：先验几纳秒能算完的（token 已在入口验过、班级名、key、条数），
    再碰索引（可能要重建，最贵）。这样灌垃圾的请求打不到 OSS。
    """
    name, err = _class_of(payload)
    if err:
        return err

    plan = payload.get("plan")
    if not isinstance(plan, dict):
        return fail(400, "bad_key", "缺少清单内容")

    key = plan.get("key")
    if not isinstance(key, str) or not key.strip():
        return fail(400, "bad_key", "清单密钥不能为空")
    if len(key) > MAX_KEY_LEN:
        return fail(400, "bad_key", f"清单密钥最长 {MAX_KEY_LEN} 个字")

    tasks = plan.get("tasks")
    if not isinstance(tasks, list) or not (MIN_TASKS <= len(tasks) <= MAX_TASKS):
        return fail(400, "bad_count", f"一份清单要 {MIN_TASKS}~{MAX_TASKS} 条，现在不对")

    if payload.get("_raw_len", 0) > MAX_BODY_BYTES:
        return fail(400, "bad_count", "清单内容太大了（上限 256KB）")

    # 学号。契约没把它列进校验表，但配额和「只能删自己的」都靠它 ——
    # 空了就变成「谁都能顶着一个空身份发」，所以这里当 bad_key 一起挡掉。
    uploader = str(payload.get("uploader") or "").strip()
    if not uploader:
        return fail(400, "bad_key", "缺少 uploader（学号）")

    ch = class_hash(name)
    pid = plan_id(key)

    try:
        idx = load_index(ch)
    except Exception as e:
        print(f"[classplan] 发布前读索引失败: {type(e).__name__}: {e}")
        return fail(502, "server_error", "清单列表暂时读不到，请稍后重试")

    plans = idx.get("plans") or []
    existing = next((p for p in plans if str(p.get("id")) == pid), None)

    # 覆盖别人的清单：key 是全班共识的名字，但发的人应该是同一个人。
    # 契约只写了 delete 的 not_owner，这里用同一个码 —— 语义是「这不是你的」。
    if existing is not None:
        owner = str(existing.get("maintainer") or "")
        if owner and owner != uploader:
            return fail(403, "not_owner", "这份清单是别人发的，不能直接覆盖")

    # 配额只在「新增」时算：更新自己那份不该被自己的另一份挤掉
    if existing is None:
        mine = sum(1 for p in plans if str(p.get("maintainer") or "") == uploader)
        if mine >= QUOTA_USER:
            return fail(409, "quota_user",
                        f"每人最多发 {QUOTA_USER} 份清单，你已经发了 {mine} 份")
        if len(plans) >= QUOTA_CLASS:
            return fail(409, "quota_class",
                        f"本班清单已经满了（最多 {QUOTA_CLASS} 份）")

    # updatedAt 用正文里的值（学委本地改的时间），没有才用服务器时间。
    # **不写回正文** —— 契约要求正文原样透传，我们只在索引和元数据里记这个值。
    updated_at = _as_int(plan.get("updatedAt")) or int(time.time() * 1000)
    body = json.dumps(plan, ensure_ascii=False, separators=(",", ":")).encode("utf-8")

    # 元数据是重建索引时的数据来源，必须和正文一致。学委名字是中文，
    # HTTP 头放不下，quote 成 ASCII；读的时候 unquote 回来。
    meta = {
        # 学号：配额计数、覆盖/删除的归属校验都靠它，**不展示**
        "x-oss-meta-maintainer": urllib.parse.quote(uploader, safe=""),
        # 姓名：只用于给同学看「谁维护的」（2026-11-22 加）。
        # 和学号分成两个字段 —— 合成一个的话，重名会串、学号又没法读。
        "x-oss-meta-owner-name": urllib.parse.quote(
            str(plan.get("maintainer") or ""), safe=""),
        # 清单名：同学在认领列表和详情页顶部看到的标题
        "x-oss-meta-name": urllib.parse.quote(str(plan.get("name") or ""), safe=""),
        "x-oss-meta-key": urllib.parse.quote(key, safe=""),
        "x-oss-meta-count": str(len(tasks)),
        "x-oss-meta-updatedat": str(updated_at),
    }
    status, _, _ = oss("PUT", plan_key(ch, pid), body=body,
                       content_type="application/json", meta=meta)
    if status not in (200, 201):
        return fail(502, "server_error", "保存失败，请稍后重试")

    brief = {"id": pid, "key": key, "maintainer": uploader,
             "ownerName": str(plan.get("maintainer") or ""),
             "name": str(plan.get("name") or ""),
             "count": len(tasks), "updatedAt": updated_at}
    plans = [p for p in plans if str(p.get("id")) != pid]
    plans.append(brief)
    write_index(ch, plans, max([_as_int(p.get("updatedAt")) for p in plans] or [0]))

    return ok(id=pid, updatedAt=updated_at)


def h_delete(payload: dict):
    """POST /classplan/delete —— 只允许删自己发的。

    归属比的是**发布时记下的学号**，不是正文里的 maintainer。

    原来拿正文 `maintainer` 比 `uploader`，但 2026-11-22 起正文那个字段放的是
    **姓名**（给同学看「谁维护」），拿名字比学号永远不相等 —— 表现成
    「谁都不能删自己的清单」。学号在对象元数据 `x-oss-meta-maintainer` 里
    （publish 写入，和 uploader 同源），从这里取；老对象没有元数据时才退回正文。

    注意这一层本来就只是「防误删」，不是安全边界：`uploader` 是客户端传的，
    真鉴权要靠登录态，那是二期的事。
    """
    name, err = _class_of(payload)
    if err:
        return err
    pid = str(payload.get("id") or "").strip()
    if not _PLAN_ID_RE.match(pid):
        return fail(404, "not_found", "这份清单不存在，可能已经被删掉了")

    uploader = str(payload.get("uploader") or "").strip()
    ch = class_hash(name)

    status, headers, raw = oss("GET", plan_key(ch, pid))
    if status == 404:
        return fail(404, "not_found", "这份清单不存在，可能已经被删掉了")
    if status != 200:
        return fail(502, "server_error", "清单暂时读不到，请稍后重试")

    h = {k.lower(): v for k, v in (headers or {}).items()}
    owner_meta = urllib.parse.unquote(h.get("x-oss-meta-maintainer", "") or "")
    try:
        plan = json.loads(raw.decode("utf-8"))
    except Exception as e:
        print(f"[classplan] 删除前解析正文失败 {pid}: {type(e).__name__}: {e}")
        return fail(502, "server_error", "清单内容损坏，无法删除")

    # 优先元数据（学号）；老对象没有 meta 时退回正文（那时候正文存的也是学号）
    owner = owner_meta or str((plan or {}).get("maintainer") or "")
    if owner != uploader:
        return fail(403, "not_owner", "只能删除自己发布的清单")

    status, _, _ = oss("DELETE", plan_key(ch, pid))
    if status not in (200, 204):
        return fail(502, "server_error", "删除失败，请稍后重试")

    # 索引走「读-改-写」；缓存本来就坏了就直接重建（顺便自愈）
    try:
        idx = read_index(ch)
        if idx is None:
            rebuild_index(ch)
        else:
            plans = [p for p in idx.get("plans") or [] if str(p.get("id")) != pid]
            write_index(ch, plans, max([_as_int(p.get("updatedAt")) for p in plans] or [0]))
    except Exception as e:
        # 删都删了，索引写失败只是下次读会重建，不能让用户看到失败再点一次
        print(f"[classplan] 删除后更新索引失败: {type(e).__name__}: {e}")

    return ok()


ROUTES = {
    "/classplan/index": h_index,
    "/classplan/get": h_get,
    "/classplan/publish": h_publish,
    "/classplan/delete": h_delete,
}


# ── 入口 ──────────────────────────────────────────────────────────

def _event_handler(event, context=None):
    req = parse_event(event)
    path = req["path"].split("?")[0].rstrip("/")

    if path.endswith("/healthz"):
        return _resp(200, {"ok": True, "time": int(time.time())})

    route = next((k for k in ROUTES if path.endswith(k)), None)
    if route is None:
        return fail(404, "not_found", "未知路径")

    # token 是契约校验表第 1 行，所有端点都要过。
    # 用 compare_digest：不让响应时间泄露「猜对了几个字符」。
    # 先 encode 成 bytes —— compare_digest 比 str 时遇到非 ASCII 会直接抛
    # TypeError，那等于「发个中文 token 就打挂服务」，这里必须绕开。
    if not hmac.compare_digest(req["headers"].get("x-app-token", "").encode("utf-8"),
                               APP_TOKEN.encode("utf-8")):
        return fail(403, "bad_token", "APP_TOKEN 不对")

    if not rate_ok(req["ip"]):
        # 契约没有限流这一行，这是个运维码（README 里有说明）
        return fail(429, "rate_limited", "请求太频繁，请稍等一下再试")

    payload = dict(req["payload"])
    payload["_raw_len"] = req["raw_len"]
    try:
        return ROUTES[route](payload)
    except Exception as e:
        # 兜底：任何没预料到的异常都不能把栈吐给客户端
        print(f"[classplan] 未捕获异常 {route}: {type(e).__name__}: {e}")
        return fail(500, "server_error", "服务器出错了，请稍后重试")


def _wsgi(environ, start_response):
    """Web 函数模式：把 WSGI 请求拼回事件那套结构，复用同一段逻辑。

    跟 qweather-proxy 一样两种入口都接住 —— FC 3.0 控制台新建函数时
    可能默认给「Web 函数」，签名是 `handler(environ, start_response)`，
    撞上事件函数那套只会看到一个 502，日志里还看不出原因。
    """
    try:
        length = int(environ.get("CONTENT_LENGTH") or 0)
    except ValueError:
        length = 0
    raw = environ["wsgi.input"].read(length) if length and "wsgi.input" in environ else b""

    headers = {}
    for k, v in environ.items():
        if k.startswith("HTTP_"):
            headers[k[5:].replace("_", "-").lower()] = v
    if environ.get("CONTENT_TYPE"):
        headers["content-type"] = environ["CONTENT_TYPE"]

    path = environ.get("PATH_INFO") or "/"
    event = {
        "rawPath": path,
        "headers": headers,
        "queryParameters": dict(urllib.parse.parse_qsl(environ.get("QUERY_STRING", ""))),
        "body": raw.decode("utf-8", "replace"),
        "isBase64Encoded": False,
        "requestContext": {
            "http": {
                "method": environ.get("REQUEST_METHOD", "POST"),
                "path": path,
                "sourceIp": environ.get("REMOTE_ADDR", "") or "unknown",
            }
        },
    }
    result = _event_handler(event, None)
    body = (result.get("body") or "").encode("utf-8")
    start_response(f"{result.get('statusCode', 200)} OK",
                   list((result.get("headers") or {}).items()))
    return [body]


def handler(event, context=None):
    """FC 入口。事件函数 / Web 函数都认（判据同 qweather-proxy）。"""
    if callable(context) and isinstance(event, dict) and "REQUEST_METHOD" in event:
        return _wsgi(event, context)
    return _event_handler(event, context)
