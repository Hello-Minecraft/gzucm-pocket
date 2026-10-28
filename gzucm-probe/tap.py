"""mitmproxy addon v2：记录完整请求头与响应体，用于复刻第三方小程序接口。

产物 data/flows_full.jsonl，一行一条 flow（request + response 合并）。
含敏感凭据，仅本地分析用，不外传、不进仓库。
"""

import json
import time
from pathlib import Path

from mitmproxy import http

OUT = Path(__file__).resolve().parent / "data" / "flows_full.jsonl"
OUT.parent.mkdir(exist_ok=True)

SKIP_HOSTS = (
    "weixin.qq.com", "qpic.cn", "gtimg.com", "wxqcloud.qq.com",
    "tencent-cloud.net", "msftconnecttest", "google.com", "gstatic.com",
    "microsoft.com", "windows.com", "bing.com", "dcloud.net.cn",
    "example.com", "favicon",
)

# 只留业务域名，减少噪音
KEEP_HOSTS = ("shengzekeji.cn", "ecard.gzucm.edu.cn", "gzucm.edu.cn")


def _keep(host: str) -> bool:
    if any(s in host for s in SKIP_HOSTS):
        return False
    return any(k in host for k in KEEP_HOSTS)


def response(flow: http.HTTPFlow) -> None:
    host = flow.request.pretty_host
    if not _keep(host):
        return
    try:
        req_body = flow.request.get_text(strict=False) or ""
    except Exception:
        req_body = "<binary>"
    try:
        resp_body = flow.response.get_text(strict=False) or ""
    except Exception:
        resp_body = "<binary>"
    rec = {
        "ts": round(time.time(), 2),
        "method": flow.request.method,
        "url": flow.request.pretty_url,
        "host": host,
        "path": flow.request.path.split("?")[0],
        "req_headers": dict(flow.request.headers),
        "req_body": req_body[:2000],
        "status": flow.response.status_code,
        "resp_headers": {k: v for k, v in flow.response.headers.items()
                         if k.lower() in ("content-type", "content-length")},
        "resp_body": resp_body[:3000],
    }
    with OUT.open("a", encoding="utf-8") as f:
        f.write(json.dumps(rec, ensure_ascii=False) + "\n")
