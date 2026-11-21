# classplan · 班级日程云端版云函数

`docs/CLASSPLAN_CLOUD.md` 的服务端实现。阿里云函数计算 FC 3.0，Python 3.10，
HTTP 触发器，**零第三方依赖**（`urllib` + 手写 OSS V1 签名，不需要 oss2，也不需要装层）。

```
classplan/v1/{classHash}/_index.json      全班清单摘要（可丢弃缓存）
classplan/v1/{classHash}/{planId}.json    一份清单正文（原样，服务端不改写）
```

---

## 1. 环境变量

控制台「函数配置 → 环境变量」里逐个加。前五个**必填**，缺任何一个函数启动即失败，
日志里会打印缺哪个（`[classplan] 缺少环境变量 OSS_AK` 这样）。

| 变量 | 必填 | 说明 | 从哪拿 |
|---|---|---|---|
| `OSS_AK` | ✅ | RAM 子账号 `gzucm-pocket-app` 的 AccessKey ID | 阿里云控制台 → RAM 访问控制 → 用户 → 创建 AccessKey |
| `OSS_SK` | ✅ | 同一个 AccessKey 的 Secret | 同上（只在创建时显示一次，丢了只能重建） |
| `OSS_BUCKET` | ✅ | 桶名，现在是 `gzucm-pocket` | OSS 控制台 → Bucket 列表 |
| `OSS_ENDPOINT` | ✅ | `https://oss-cn-guangzhou.aliyuncs.com`（不带 `https://` 也会自动补） | OSS 控制台 → 概览 → 访问域名（**外网 Endpoint**，不要用内网那个） |
| `APP_TOKEN` | ✅ | 与 `qweather-proxy` **同一个**串，App 里 `X-App-Token` 带的就是它 | 自己定的一串随机字符，两端写一样 |
| `RATE_LIMIT_PER_MIN` | ⬜ | 每实例每分钟请求上限，默认 `60` | 不用填 |

> AK/SK 只放函数环境变量。**不要**写进仓库、不要写进 APK、不要写进任何脚本。
> 本地调试的 AK 在 `.secrets/oss.properties`（已 gitignore），
> 用 `export` 临时注入，测完 `unset`。

RAM 权限只需要 `gzucm-pocket` 桶的 `PutObject / GetObject / DeleteObject /
ListObjects / HeadObject`，不要给其它云资源权限。

---

## 2. 触发器与函数配置

| 项 | 值 |
|---|---|
| 函数类型 | **事件函数**（Web 函数也能跑，见下） |
| 运行环境 | Python 3.10 |
| 函数入口 | `index.handler` |
| 触发器类型 | **HTTP 触发器** |
| 请求方法 | `POST, GET, OPTIONS`（至少 POST / GET） |
| 鉴权方式 | **无需鉴权**（自己用 `X-App-Token` 兜一层） |
| 超时 | 15 秒（默认 3 秒不够，重建索引要连发十来个 HEAD） |
| 内存 | 128 MB 足够 |

代码只有 `index.py` 一个文件，直接在控制台「在线编辑」粘贴即可；用 `zip` 上传就
把 `index.py` 放在 zip 根目录。

> **已经打好的包（2026-11-22 02:53）**：`oss://gzucm-pocket/deploy/classplan.zip`
> （12058 字节）。控制台建函数时选「通过 OSS 上传」可以直接指这个路径，
> 省得在本地找文件 —— 前提是给 FC 授予读该桶的权限（选 OSS 上传时会引导建角色）。
> 本地也有一份 `serverless/classplan/index.py`，改完代码要重新打包上传。

`handler` 同时兼容两种函数类型：

- **事件函数** → `handler(event, context)`，`event` 里读 `rawPath` / `headers` /
  `body` / `isBase64Encoded` / `requestContext.http`
- **Web 函数** → `handler(environ, start_response)`

所以建错类型也能跑，不用为了「事件 / Web」返工。

> HTTP 触发器的路径是带前缀的：如果创建触发器时填的路径是 `/`，访问
> `<URL>/classplan/index` 就落到本函数；如果填了 `/api`，实际访问是
> `<URL>/api/classplan/index`，代码用的是 `endswith` 匹配，两种都认。

---

## 3. 部署后自检

把 `<URL>` 换成触发器给的公网地址，`<TOKEN>` 换成 `APP_TOKEN`。

**① 存活**

```bash
curl -s "<URL>/healthz"
# {"ok": true, "time": 1732350000}
```

**② token 是否生效**

```bash
curl -s -X POST "<URL>/classplan/index" \
  -H "Content-Type: application/json" \
  -H "X-App-Token: wrong" \
  -d '{"class":"一院23中医(铁涛)5"}'
# {"ok":false,"error":"bad_token","message":"APP_TOKEN 不对"}
```

**③ 列一个空班**（没发过清单的班，应返回空数组而不是报错）

```bash
curl -s -X POST "<URL>/classplan/index" \
  -H "Content-Type: application/json" \
  -H "X-App-Token: <TOKEN>" \
  -d '{"class":"测试班-未发布过"}'
# {"ok":true,"updatedAt":0,"plans":[]}
```

**④ 读写删一条龙**

```bash
# 发布
curl -s -X POST "<URL>/classplan/publish" \
  -H "Content-Type: application/json" -H "X-App-Token: <TOKEN>" \
  -d '{"class":"测试班","uploader":"2023014093",
       "plan":{"key":"张三","maintainer":"2023014093","className":"测试班",
               "updatedAt":1732350000000,
               "tasks":[{"id":"t1","title":"自检条目","updatedAt":1732350000000}]}}'
# {"ok":true,"id":"<6位hex>","updatedAt":1732350000000}

# 读列表 / 读正文（id 用上一步返回的）
curl -s -X POST "<URL>/classplan/index" -H "Content-Type: application/json" \
  -H "X-App-Token: <TOKEN>" -d '{"class":"测试班"}'
curl -s -X POST "<URL>/classplan/get" -H "Content-Type: application/json" \
  -H "X-App-Token: <TOKEN>" -d '{"class":"测试班","id":"<6位hex>"}'

# 删
curl -s -X POST "<URL>/classplan/delete" -H "Content-Type: application/json" \
  -H "X-App-Token: <TOKEN>" -d '{"class":"测试班","id":"<6位hex>","uploader":"2023014093"}'
# {"ok":true}
```

**⑤ 删索引验自愈**（可选，但最能说明「索引只是缓存」）

用 OSS 控制台删掉 `classplan/v1/<测试班hash>/_index.json`，再调一次 ③ 的
`index`，应该仍能列出刚才那份清单（服务端 ListObjects + HEAD 重建并回写）。

---

## 4. 错误码

契约里的（`docs/CLASSPLAN_CLOUD.md` 2.3 / 2.4）：

| HTTP | `error` | 什么时候 |
|---|---|---|
| 403 | `bad_token` | `X-App-Token` 不对或没带 |
| 400 | `bad_class` | `class` 缺失 / 空 / 超过 40 字 |
| 400 | `bad_key` | `plan.key` 缺失 / 空 / 超过 12 字；或 `uploader` 缺失 |
| 400 | `bad_count` | `tasks` 不是 1~50 条；或正文超过 256KB |
| 409 | `quota_user` | 同一 `uploader` 在本班已发 2 份，还要新增 |
| 409 | `quota_class` | 本班已有 8 份，还要新增 |
| 404 | `not_found` | 清单不存在 / `id` 形状不对 / 路径不认识 |
| 403 | `not_owner` | 删别人的清单；或覆盖别人已发的同一 key |

契约之外的两个**运维码**（契约没写，但线上必须有，否则只能回 200 或裸 500）：

| HTTP | `error` | 说明 |
|---|---|---|
| 429 | `rate_limited` | 单实例每分钟超过 60 次 |
| 500/502 | `server_error` | OSS 不通 / 正文损坏 / 未捕获异常。细节只进日志 |

响应体永远是 `{"ok":false,"error":"<码>","message":"<中文人话>"}`，`message`
可以直接显示给同学。

---

## 5. 设计要点与已知边界

- **索引可丢**：`_index.json` 读不到、解析失败、结构不对，一律 ListObjects +
  逐个 HEAD 重建再回写。单条对象坏了只跳过它，不影响整班。索引写失败只打日志，
  不会把一次成功的发布变成 500。
- **元数据是重建的数据源**：`x-oss-meta-maintainer / -key / -count / -updatedat`。
  学委名字是中文，HTTP 头只能放 ASCII，所以 key / maintainer 写入前 `quote`、
  读出时 `unquote`。元数据缺失的对象会退回读正文，老数据也能重建。
- **`updatedAt` 取正文里的值**（学委本地改的时间），正文没带才用服务器时间；
  这个值只写进索引和元数据，**不改正文**。
- **配额按 `maintainer` 统计**：云端版 `uploader` 就是学号，元数据里记的也是它。
  更新自己那份不占新额度。
- **并发**：发布是「读索引 → 写对象 → 写索引」，没有事务；两个请求同时新增时
  理论上有几毫秒的窗口能突破配额（比如同时变成 3 份）。一个班一般一个学委，
  为此上分布式锁不值得。
- **限流是进程内的**：FC 会扩容、会回收，挡不住分布式刷，只防「一个班同时
  打开把 OSS QPS 打满」。跟 `qweather-proxy` 一个性质，别当安全边界。
- **鉴权模型**：`uploader` / `class` 都是客户端上报的，服务端验不了真伪。
  这是「可追溯 + 有配额」，不是身份认证。详见契约第 3 节。
- **不要给 `classplan/` 前缀开公共读**：桶保持「阻止公共访问」，读写全走本函数。
