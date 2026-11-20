# -*- coding: utf-8 -*-
"""
和风天气转发云函数（阿里云函数计算 FC 3.0，Python 3.10，HTTP 触发器）

## 为什么是「转发」而不是「发 token」

原来那份代码（云函数代码.txt）做的是：调用一次就把 JWT 返回给客户端，
再由 App 直接去请求和风。这等于把「钥匙」复制给每一个人 ——
拿到 URL 的任何人都能领一枚 15 分钟有效的 token，然后绕过你直接刷和风，
额度算在你头上，你还没有任何地方能拦。

这份改成真正的转发：JWT 只在函数进程内签、只在这里用，
公网只暴露「某个城市的天气」这一件事。

    App ──> FC（本文件） ──> 和风
             ├─ 路径白名单：只认 /weather/now、/weather/7d
             ├─ 城市写死（LOCATION），客户端改不了
             ├─ 结果缓存 30 分钟（保护额度，也更快）
             ├─ 可选 X-App-Token 校验（防脚本乱刷，不是强认证）
             └─ 每实例每分钟限流

## 部署

1. 新建函数：运行环境 Python 3.10，**HTTP 触发器**，函数入口 `index.handler`，
   本文件重命名为 `index.py`
2. 依赖：`cryptography`。内置运行时不一定带 —— 先试 `import`，
   报 ImportError 就在「层」里加一层（本地 `pip install cryptography -t python/`
   再打成 zip 上传，目录结构为 `python/cryptography/...`）
3. 环境变量（全部必填，除了 APP_TOKEN / 缓存参数）：

   | 变量 | 说明 |
   |---|---|
   | `QWEATHER_PROJECT_ID` | 控制台「项目」里的 Project ID |
   | `QWEATHER_KEY_ID` | 凭据的 ID（JWT header 的 kid） |
   | `QWEATHER_PRIVATE_KEY` | `ed25519-private.pem` 的**全文**（含 BEGIN/END 两行） |
   | `QWEATHER_HOST` | 你的专属 API Host，如 `https://abcd1234.re.qweatherapi.com` |
   | `QWEATHER_LOCATION` | 城市 LocationID，广州是 `101280101`（可省） |
   | `APP_TOKEN` | 和 App 里约定的串；留空则不校验 |
   | `CACHE_SECONDS` | 缓存秒数，默认 1800 |
   | `RATE_LIMIT_PER_MIN` | 每 IP 每分钟上限，默认 30 |

4. 触发器鉴权选「无需鉴权」（自己用 APP_TOKEN 兜一层），或「签名认证」——
   后者 App 端要算签名，麻烦，这里不推荐
5. 部署后先访问 `<URL>/healthz`，应该返回 `{"ok": true}`

## 已知边界

- 限流是**进程内**的：FC 会横向扩容、实例会被回收，所以它挡不住分布式刷。
  真要严格限流得走 API 网关的流控或 Redis，这个量级（自己和同学用）不值当。
- 缓存同理，多个实例各存一份。目的只是把「每人每次打开都请求」压成「每半小时一次」。
- 私钥放在控制台环境变量里，能看到的子账号就能看到它。别把生产账号借人。
"""

import base64
import gzip
import hashlib
import json
import os
import time
import urllib.error
import urllib.parse
import urllib.request

try:
    from cryptography.hazmat.primitives import serialization as _serialization
except Exception:          # FC 内置运行时不一定带；下面有纯 Python 兜底
    _serialization = None

PROJECT_ID = os.environ.get("QWEATHER_PROJECT_ID", "").strip()
# 开发者ID（Q 开头那串，控制台-设置）。**JWT 的 iss 必填**，
# 少这一个 claim 会一直 401「Authentication failed」，而且和风的报错不会告诉你缺哪个字段。
DEVELOPER_ID = os.environ.get("QWEATHER_DEVELOPER_ID", "").strip()
KEY_ID = os.environ.get("QWEATHER_KEY_ID", "").strip()
# 控制台里粘贴时换行常被吃成字面量 \n，这里兼容两种
PRIVATE_PEM = os.environ.get("QWEATHER_PRIVATE_KEY", "").replace("\\n", "\n").strip()
QWEATHER_HOST = os.environ.get("QWEATHER_HOST", "https://devapi.qweather.com").strip().rstrip("/")
LOCATION = os.environ.get("QWEATHER_LOCATION", "101280101").strip()
APP_TOKEN = os.environ.get("APP_TOKEN", "").strip()
# 凭据开了「Android 应用」限制时才有用；两个都为空就不发这两个头。
# 请求是从 FC 发出去的，不是从手机，所以和风看不到真实的应用签名 —— 只能代填。
ANDROID_PKG = os.environ.get("QWEATHER_ANDROID_PACKAGE", "").strip()
ANDROID_CERT = os.environ.get("QWEATHER_ANDROID_CERT", "").strip().replace(":", "").upper()
CACHE_SECONDS = int(os.environ.get("CACHE_SECONDS", "1800"))
RATE_LIMIT_PER_MIN = int(os.environ.get("RATE_LIMIT_PER_MIN", "30"))

# 路径白名单：左边是 App 请求的路径后缀，右边是和风的真实路径
ROUTES = {
    "/weather/now": "/v7/weather/now",
    "/weather/7d": "/v7/weather/7d",
}

_token = {"jwt": None, "exp": 0}
_cache = {}   # route_key -> (expire_at, body_text)
_rate = {}    # ip -> [window_start, count]


# ── 纯 Python Ed25519 兜底（RFC 8032 参考实现） ────────────────────
#
# 为什么要有这个：阿里云 FC 的 Python 运行时不一定预装 cryptography，
# 一旦缺包整个函数 500，报错还只在日志里。这份实现只有几十行，
# 签一次约几十毫秒 —— JWT 缓存 20 分钟，摊下来可以忽略。
# 有 cryptography 时优先用它（更快），没有就自动走这里，部署时不用再操心「层」。
#
# 代码照 RFC 8032 §6 的参考实现（慢速版）搬的，只做了三处整理：
# 变量名、把 sha512 换成 hashlib、加类型标注。数学部分一个字没改。

_P = 2**255 - 19
_Q = 2**252 + 27742317777372353535851937790883648493


def _inv(x: int) -> int:
    return pow(x, _P - 2, _P)


_D = -121665 * _inv(121666) % _P
_SQRT_M1 = pow(2, (_P - 1) // 4, _P)


def _x_recover(y: int) -> int:
    xx = (y * y - 1) * _inv(_D * y * y + 1)
    x = pow(xx, (_P + 3) // 8, _P)
    if (x * x - xx) % _P != 0:
        x = (x * _SQRT_M1) % _P
    if x % 2 != 0:
        x = _P - x
    return x


_BY = 4 * _inv(5) % _P
_BX = _x_recover(_BY)
# 扩展坐标 (X, Y, Z, T)，避免每次点加都做一次求逆
_B = (_BX % _P, _BY % _P, 1, _BX * _BY % _P)
_IDENT = (0, 1, 1, 0)


def _add(P, Q):
    x1, y1, z1, t1 = P
    x2, y2, z2, t2 = Q
    a = (y1 - x1) * (y2 - x2) % _P
    b = (y1 + x1) * (y2 + x2) % _P
    c = t1 * 2 * _D * t2 % _P
    d = z1 * 2 * z2 % _P
    e, f, g, h = b - a, d - c, d + c, b + a
    return (e * f % _P, g * h % _P, f * g % _P, e * h % _P)


def _mul(s: int, P):
    Q = _IDENT
    while s > 0:
        if s & 1:
            Q = _add(Q, P)
        P = _add(P, P)
        s >>= 1
    return Q


def _encode(P) -> bytes:
    x, y, z, _ = P
    zi = _inv(z)
    x, y = x * zi % _P, y * zi % _P
    bits = [(y >> i) & 1 for i in range(255)] + [x & 1]
    return bytes(sum(bits[i * 8 + j] << j for j in range(8)) for i in range(32))


def _secret_scalar(seed: bytes) -> int:
    h = hashlib.sha512(seed).digest()
    a = 2**254 + sum(2**i * ((h[i // 8] >> (i % 8)) & 1) for i in range(3, 254))
    return a


def ed25519_public(seed: bytes) -> bytes:
    return _encode(_mul(_secret_scalar(seed), _B))


def ed25519_sign(seed: bytes, msg: bytes) -> bytes:
    """RFC 8032 §5.1.6。返回 64 字节签名。"""
    h = hashlib.sha512(seed).digest()
    a = _secret_scalar(seed)
    prefix = h[32:]
    pub = _encode(_mul(a, _B))
    r = int.from_bytes(hashlib.sha512(prefix + msg).digest(), "little") % _Q
    big_r = _encode(_mul(r, _B))
    k = int.from_bytes(hashlib.sha512(big_r + pub + msg).digest(), "little") % _Q
    s = (r + k * a) % _Q
    return big_r + s.to_bytes(32, "little")


def _seed_from_pem(pem: str) -> bytes:
    """从 PKCS#8 的 PEM 里取出 32 字节种子（DER 末尾那 32 字节）。"""
    body = "".join(l for l in pem.splitlines() if l and not l.startswith("-----"))
    der = base64.b64decode(body)
    if len(der) < 32:
        raise ValueError("私钥格式不对")
    return der[-32:]


def _sign_raw(data: bytes) -> bytes:
    if _serialization is not None:
        key = _serialization.load_pem_private_key(PRIVATE_PEM.encode(), password=None)
        return key.sign(data)
    return ed25519_sign(_seed_from_pem(PRIVATE_PEM), data)


# ── JWT（Ed25519） ────────────────────────────────────────────────

def _b64url(raw: bytes) -> str:
    return base64.urlsafe_b64encode(raw).rstrip(b"=").decode("ascii")


def _sign_jwt() -> str:
    """签一枚 JWT，进程内缓存到快过期为止。

    和风规定 exp - iat 不超过 24 小时；这里给 20 分钟，
    留 5 分钟余量就重签 —— 签一次不到 1ms，但没必要每次请求都动私钥。
    """
    now = int(time.time())
    if _token["jwt"] and _token["exp"] - now > 300:
        return _token["jwt"]

    if not (PROJECT_ID and KEY_ID and PRIVATE_PEM and DEVELOPER_ID):
        raise RuntimeError("缺少 QWEATHER_PROJECT_ID / KEY_ID / DEVELOPER_ID / PRIVATE_KEY")

    header = _b64url(json.dumps({"alg": "EdDSA", "kid": KEY_ID},
                                separators=(",", ":")).encode())
    # 四个 claim 一个都不能少：iss=开发者ID、sub=项目ID、iat、exp
    payload = _b64url(json.dumps({"iss": DEVELOPER_ID, "sub": PROJECT_ID,
                                  "iat": now - 30, "exp": now + 1200},
                                 separators=(",", ":")).encode())
    signing_input = f"{header}.{payload}".encode("ascii")

    signature = _sign_raw(signing_input)

    _token["jwt"] = f"{header}.{payload}.{_b64url(signature)}"
    _token["exp"] = now + 1200
    return _token["jwt"]


# ── 上游请求 ──────────────────────────────────────────────────────

def _decode(raw: bytes) -> str:
    """把上游字节解成文本。

    **不看 `Content-Encoding`，只看 gzip 魔数（1f 8b）**：实测和风的错误响应
    是 gzip 压缩的，但头里不一定带 `Content-Encoding`；照着那个头判断就会
    把 gzip 字节当 UTF-8 解，回给 App 的是一整串 �，看着像「接口坏了」。
    """
    if raw[:2] == b"\x1f\x8b":
        try:
            raw = gzip.decompress(raw)
        except Exception:
            pass          # 解不开就原样返回，至少还能在日志里看到长度
    return raw.decode("utf-8", "replace")


def _upstream(qweather_path: str) -> str:
    url = f"{QWEATHER_HOST}{qweather_path}?location={urllib.parse.quote(LOCATION)}"
    headers = {
        "Authorization": f"Bearer {_sign_jwt()}",
        "Accept": "application/json",
        # 让和风按 gzip 回，然后手动解 —— 省流量，FC 出网也快一点
        "Accept-Encoding": "gzip",
    }
    if ANDROID_PKG and ANDROID_CERT:
        # 控制台把凭据限制成「Android 应用」时，和风要求带这两个头。
        # 文档原话是「使用第三方 SDK 或 API，请在请求头加入 …」——
        # 校验的是值是否匹配，而这两个值本来就是公开的（包名在 manifest、
        # SHA-1 在 APK 签名里），所以它只是个门帘，真正的防线是 APP_TOKEN + 限流。
        headers["X-Android-Package-Name"] = ANDROID_PKG
        headers["X-Android-Cert"] = ANDROID_CERT
    req = urllib.request.Request(url, headers=headers)
    with urllib.request.urlopen(req, timeout=8) as resp:
        return _decode(resp.read())


# ── 限流（尽力而为） ──────────────────────────────────────────────

def _rate_ok(ip: str) -> bool:
    now = int(time.time())
    slot = _rate.get(ip)
    if not slot or now - slot[0] >= 60:
        _rate[ip] = [now, 1]
        return True
    if slot[1] >= RATE_LIMIT_PER_MIN:
        return False
    slot[1] += 1
    return True


# ── 事件解析 / 响应 ───────────────────────────────────────────────

def _parse(event):
    if isinstance(event, (bytes, bytearray)):
        event = json.loads(event.decode("utf-8"))
    if isinstance(event, str):
        event = json.loads(event)
    if not isinstance(event, dict):
        event = {}

    headers = {}
    for k, v in (event.get("headers") or {}).items():
        headers[str(k).lower()] = v

    rc = event.get("requestContext") or {}
    http = rc.get("http") or {}
    path = event.get("rawPath") or event.get("path") or http.get("path") or "/"
    method = http.get("method") or event.get("httpMethod") or "GET"
    ip = http.get("sourceIp") or headers.get("x-forwarded-for", "").split(",")[0].strip() or "unknown"
    return {"path": path, "method": method, "headers": headers, "ip": ip}


def _resp(status: int, body: str, cache_state: str = "BYPASS"):
    return {
        "statusCode": status,
        "headers": {
            "Content-Type": "application/json;charset=utf-8",
            # token 与实时数据都不该被中间层缓存
            "Cache-Control": "no-store",
            "X-Cache": cache_state,
        },
        "body": body,
    }


def _fail(status: int, qweather_code: str, msg: str):
    """故意返回和风形状的 body：App 的解析器只认 code == "200"，
    这样错误会自然落到「拿不到数据」的分支，不需要客户端加新逻辑。"""
    return _resp(status, json.dumps({"code": qweather_code, "msg": msg}, ensure_ascii=False))


# ── 入口 ──────────────────────────────────────────────────────────

def _event_handler(event, context):
    req = _parse(event)
    path = req["path"].split("?")[0].rstrip("/")

    if path.endswith("/healthz"):
        return _resp(200, json.dumps({"ok": True, "time": int(time.time())}))

    route_key = next((k for k in ROUTES if path.endswith(k)), None)
    if route_key is None:
        return _fail(404, "404", "未知路径")

    if req["method"].upper() != "GET":
        return _fail(405, "405", "只支持 GET")

    if APP_TOKEN and req["headers"].get("x-app-token", "") != APP_TOKEN:
        return _fail(403, "403", "forbidden")

    if not _rate_ok(req["ip"]):
        return _fail(429, "429", "请求太频繁")

    now = time.time()
    hit = _cache.get(route_key)
    if hit and hit[0] > now:
        return _resp(200, hit[1], "HIT")

    try:
        body = _upstream(ROUTES[route_key])
    except urllib.error.HTTPError as e:
        # 和风把业务码放 body 里（401 凭据无效 / 403 应用限制 / 429 超额 / 404 城市错）。
        # 原样透给 App：它那边会把 code 显示出来，配错时一眼能看出是哪一类，
        # 比统一回一句「上游失败」有用得多。只写日志不写响应体的原则在这里让位给可排查性，
        # 因为 body 是和风自己的错误信息，不含我们的路径和依赖。
        try:
            upstream_body = _decode(e.read())
        except Exception:
            upstream_body = ""
        print(f"[weather] upstream HTTP {e.code}: {upstream_body[:300]}")
        if e.code in (401, 403) and not (ANDROID_PKG and ANDROID_CERT):
            print("[weather] 若控制台给凭据开了「应用限制」，必须配 "
                  "QWEATHER_ANDROID_PACKAGE / QWEATHER_ANDROID_CERT，否则请求一律被拒")
        if e.code == 401:
            print("[weather] 401 常见原因：HOST 不是控制台给的专属域名、凭据ID 填错、"
                  "或私钥与凭据不是同一对")
        out = _resp(200, upstream_body or json.dumps({"code": str(e.code)}), "ERROR")
        # 把上游的 HTTP 状态也放出来：App 只看 body 里的和风业务码，
        # 排查时用 curl -D - 能直接看到 401 还是 403，省一次翻日志
        out["headers"]["X-Upstream-Status"] = str(e.code)
        return out
    except Exception as e:
        # 细节只写日志，不回给客户端（避免把内部路径、库版本吐出去）
        print(f"[weather] upstream failed: {type(e).__name__}: {e}")
        return _fail(502, "502", "上游请求失败")

    # 只有 code=200 才缓存，否则会把一次偶发错误缓存半小时
    try:
        if json.loads(body).get("code") == "200":
            _cache[route_key] = (now + CACHE_SECONDS, body)
    except Exception:
        pass

    return _resp(200, body, "MISS")


# ── 入口（事件函数 / Web 函数都能跑） ─────────────────────────────

def _wsgi(environ, start_response):
    """Web 函数模式：把 WSGI 请求拼回事件函数那套结构，复用同一段逻辑。

    FC 3.0 控制台新建函数时可能默认给「Web 函数」，那时的入口签名是
    `handler(environ, start_response)`，跟事件函数的 `handler(event, context)`
    完全不是一回事 —— 撞上就只能看到一个 502，日志里还看不出原因。
    这里把两种都接住，部署时不用先确认类型。
    """
    length = int(environ.get("CONTENT_LENGTH") or 0)
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
                "method": environ.get("REQUEST_METHOD", "GET"),
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
    """FC 入口。两种函数类型都认：

    · **事件函数** → `handler(event, context)`，event 是 dict，正常路径
    · **Web 函数** → `handler(environ, start_response)`，第二个参数是个 callable
    """
    if callable(context) and isinstance(event, dict) and "REQUEST_METHOD" in event:
        return _wsgi(event, context)
    return _event_handler(event, context)
