# 天气转发：为什么、怎么部署、App 怎么接

> 代码在 [`serverless/qweather-proxy/index.py`](../serverless/qweather-proxy/index.py)，
> 桌面 `vibecoding/广中医口袋/和风天气/云函数-代理版.py` 是同一份（方便直接上传）。

## 一、为什么不让 App 直接拿 token

最初（DeepSeek 写的那版）是「云函数返回 JWT，App 拿 token 直接请求和风」。
这解决了一个真问题 —— JWT 要用 Ed25519 私钥现签，私钥不能进 APK ——
但它把钥匙复制给了每个能访问这个 URL 的人：

- 端点没有任何鉴权：`GET /` 就给你一枚 15 分钟有效的 token
- 拿到 token 就能绕过你的函数直连和风，额度算在你头上
- 你没有任何地方能拦、能限、能看

**改成转发**之后，公网只暴露「某个城市的天气」这一件事：

```
App ──> FC ──> 和风
        ├─ 只认 /weather/now 和 /weather/7d（路径白名单）
        ├─ 城市写死在服务端，客户端连参数都不用传
        ├─ 结果缓存 30 分钟
        ├─ 每 IP 每分钟 30 次（尽力而为）
        └─ 私钥只在函数里，永不出服务端
```

顺带解决了额度：缓存命中时根本不碰和风，20 个人每天开 10 次也只消耗几十次请求。

## 二、包名不能用来限制访问

App 的包名是 **`com.gzucm.pocket`**（`applicationId` 与 `namespace` 相同）。

但它**不是**一个可以拿来当密码的东西：

- 包名是公开信息，任何反编译或看一眼应用商店都能拿到
- 就算你在请求头里带 `X-Package: com.gzucm.pocket`，对方想冒充只需要抄一行 header
- 服务端也**无法验证**这个头 —— 它没有任何签名意义

真要限制访问，能做的只有这几层，按性价比排：

| 手段 | 能挡住什么 | 代价 |
|---|---|---|
| **转发 + 缓存 + 限流**（已做） | 挡住额度被刷爆 | 无 |
| `X-App-Token` 共享串（已做） | 挡住随手扫到 URL 的脚本 | 一个串写进 APK，能被提取，但可随时更换 |
| API 网关流控 / Redis 计数 | 分布式刷 | 要额外配置，这个量级不值得 |
| 每个用户一个 token + 登录 | 真·防滥用 | 要为几十个人做账号体系，不值 |

结论：**包名不用来做鉴权**，`X-App-Token` 只当门帘。真正的护栏是「缓存 + 限流 + 只暴露一个城市的天气」，
就算门帘被掀开，损失上限也就是一天几十次请求。

## 二·五、控制台那几栏怎么填

对照「项目管理 → 凭据」里那张表单：

| 控制台字段 | 该填什么 | 落到哪 |
|---|---|---|
| 凭据ID（例：`THGV25WD4C`） | 照抄 | 环境变量 `QWEATHER_KEY_ID`，就是 JWT 的 `kid` |
| 项目ID | 项目页里那一串 | 环境变量 `QWEATHER_PROJECT_ID`，就是 JWT 的 `sub` |
| 凭据名称 | 随便，建议 `gzucm-pocket` | — |
| 应用限制 | **建议「不限制」** | 见下 |
| API 限制 | 勾 **天气预报**（一个就够） | 见下 |

### 顶栏那串 SHA-256 不是签名指纹

凭据页「创建于」下面那行 `SHA-256: 5bc2…` 是**凭据自己的标识**，跟签名证书无关。
实测：拿桌面那对 pem 按 DER、原始 32 字节、JWK thumbprint 三种口径算 SHA-256，
都对不上它 —— 所以它也不该被当成「校验密钥有没有传对」的依据。下面「证书SHA-1指纹」那个框要的是
**keystore 里那张证书**的 SHA-1，填错会让每一个请求都被拒。

当前 release 签名证书（`app/gzucm-pocket.jks`，别名 `gzucmpocket`）：

```
7A:5C:AE:E2:EC:9F:E7:C8:83:51:B2:6F:30:DB:AD:B6:96:00:D8:9A
```

换 keystore 之后重新取：

```bash
keytool -list -v -keystore app/gzucm-pocket.jks -alias gzucmpocket | grep SHA1
```

### 应用限制：能开，但对这套架构没意义

和风的安全指南写得很明确 —— 限制成「Android 应用」之后，
**「使用第三方 SDK 或 API，请在请求头加入 `X-Android-Package-Name` 与 `X-Android-Cert`」**。
我们的请求是从阿里云 FC 发出去的，不是手机，所以：

- 开了限制就必须让函数代填这两个头（`index.py` 已支持，配上面那两个环境变量即可），
  否则 `/weather/now` 会一路 401/403，App 上只显示「天气暂时取不到」
- 而这两个值本身都是公开的：包名在 manifest 里，SHA-1 用
  `apksigner verify --print-certs app-release.apk` 就能打出来

也就是说，在「App → 自己的函数 → 和风」这个链路里，它对**有心人**的保护作用接近 0，
却多了一处能把服务整体弄挂的开关。所以默认建议「不限制」——
私钥本来就只存在函数环境变量里，根本没有东西可以被前端偷走。

### API 限制：勾「天气预报」一个就够

这一栏列的是**数据包**，不是单个接口。`/v7/weather/now`（城市实时天气）和
`/v7/weather/7d`（城市每日预报）都在「天气预报」包里，勾这一项就能同时调通。
其余数据包（GeoAPI、分钟降水、空气质量、天文……）我们一个都不用，别勾，
勾得越少，万一凭据泄漏时能被拿走的东西越少。

## 三、部署（阿里云 FC 3.0）

1. 新建函数：Python 3.10、**HTTP 触发器**、入口 `index.handler`，上传 `index.py`。
   函数类型选「事件函数」最直接；**万一控制台给的是「Web 函数」也不用改代码** ——
   入口对两种签名都做了适配（`handler(event, context)` 与 `handler(environ, start_response)`）
2. **单实例并发度动不了，也不用动**：内置运行时（Python 3.9/3.10/3.12）把它锁死为 1，
   想改只能换自定义运行时或自定义镜像 —— 那要从「单文件上传」变成维护一条构建流水线，
   换来的只是偶发冷启动少一次。本项目只有两个串行请求、上游又有 30 分钟缓存，
   并发度 1 的实际代价可以忽略。别为了它折腾
3. 依赖：**不用配「层」**。函数里 `cryptography` 是可选的 —— 有就用（快），没有自动切到
   文件内的纯 Python Ed25519（RFC 8032 参考实现，签一次几十毫秒，而 JWT 缓存 20 分钟，
   摊下来可以忽略）。单文件上传即可跑
4. 环境变量：

   | 变量 | 值 | 必填 |
   |---|---|---|
   | `QWEATHER_PROJECT_ID` | 控制台项目 ID | ✅ |
   | `QWEATHER_KEY_ID` | 凭据 ID（JWT 的 `kid`） | ✅ |
   | `QWEATHER_DEVELOPER_ID` | **开发者ID**（`Q` 开头的 10 位，控制台-设置），JWT 的 `iss` | ✅ |
   | `QWEATHER_PRIVATE_KEY` | `ed25519-private.pem` **全文**（含首尾两行） | ✅ |
   | `QWEATHER_HOST` | 专属 API Host，形如 `https://abcd1234.re.qweatherapi.com` | ✅ |
   | `QWEATHER_LOCATION` | 城市 ID，广州 `101280101` | 可选 |
   | `APP_TOKEN` | 与 App 里 `app_token` 填同一个串 | 建议 |
   | `QWEATHER_ANDROID_PACKAGE` | 凭据开了「Android 应用」限制时填 `com.gzucm.pocket` | 只有开限制才要 |
   | `QWEATHER_ANDROID_CERT` | 同上，release 签名证书 SHA-1（**带不带冒号都行**，函数会去掉冒号再发） | 只有开限制才要 |
   | `CACHE_SECONDS` | 缓存秒数，默认 1800 | 可选 |
   | `RATE_LIMIT_PER_MIN` | 每 IP 每分钟次数，默认 30 | 可选 |

5. 触发器鉴权选「无需鉴权」（`APP_TOKEN` 已经在函数里兜了一层）
6. 部署后先访问 `https://<你的域名>/healthz`，返回 `{"ok": true}` 就通了
7. 再访问 `/weather/now`，应该看到和风原始 JSON（`{"code":"200","now":{...}}`）

## 四、App 怎么接

`weather.properties`（已 gitignore，不进仓库）：

```properties
base=https://xxxxx.cn-hangzhou.fcapp.run
app_token=和 FC 环境变量 APP_TOKEN 相同的串
```

App 端行为：

- 填了 `base` → 只请求 `<base>/weather/now` 与 `<base>/weather/7d`，带 `X-App-Token`
- 没填 `base`、填了 `key` → 退回直连和风（只有 API Key 模式走得通；**JWT 模式不行**，
  因为私钥不能进 APK）
- 两个都没填 → 天气卡整块不渲染，设置里的那一行置灰

服务端返回的就是和风原始 JSON，App 的解析代码一行都不用改 ——
转发层只做鉴权、缓存和限流，不重新加工数据。这样以后和风加字段也不会被函数吃掉。

## 五、验证过什么、没验证什么

- ✅ 函数逻辑（JWT 结构、路径白名单、缓存键、错误形状）在本地读过一遍；
      JWT 用 `kid` + `sub` + `iat`/`exp`（20 分钟），与和风 JWT 文档一致
- ✅ 桌面那对 `ed25519-*.pem` 用 `openssl pkey -pubout` 验过**确实配对**
      （私钥推导出的公钥与 `ed25519-public.pem` 逐字节相同），私钥是 PKCS#8 Ed25519，
      OpenSSL 3.5 能正常解析 —— 和 `cryptography` 用的是同一套解析器，不会在函数里炸
- ✅ **JWT 端到端验过**：用这份私钥签一整枚 token（`{"alg":"EdDSA","kid":"THGV25WD4C"}` +
      `{"sub":…,"iat":…,"exp":…}`），再用 `openssl pkeyutl -verify -rawin` 拿公钥验签，
      输出 `Signature Verified Successfully`。纯 Python 兜底与 `cryptography` 同源 RFC 8032
- ✅ **线上跑通了**：`/weather/now` 与 `/weather/7d` 都返回 `code:200`，广州 12° 阴
- ⚠️ **踩过的坑：JWT 少写 `iss` 会一直 401**。和风的报错只会说
      「Authentication failed, check your KEY/Token or Host」，不会告诉你缺哪个 claim ——
      四个字段 `iss`(开发者ID) / `sub`(项目ID) / `iat` / `exp` 缺一不可
- ⚠️ **401 与 403 是两件事**：401 = 签名或 claim 不对；403（`Security Restriction`）=
      签名对了但被「应用限制」拦下，这时才需要补 `X-Android-*` 两个头
- ⚠️ 编码坑：私钥文件是纯 ASCII，但 `ed25519-public.pem` 被存成了 **UTF-16 LE**（开头 `FF FE`）。
      往任何地方粘贴时都要转成纯 ASCII，否则首字符会变成乱码 —— 函数的兜底实现会先把 PEM
      规整成 base64 再解码，正文里混进零字节就会签名失败
- ✅ 私钥文件确认是 **Ed25519**（PKCS#8，`MCowBQYDK2Vw…` 开头的是配套公钥）
- ⚠️ 没有真实 key，**没跑过真实签名**；部署后第一次请求如果返回 401，
  先检查 `QWEATHER_HOST` 是不是你的专属域名（老账号才用 `devapi.qweather.com`）
- ⚠️ `cryptography` 是否预装取决于运行时版本，见第 3 步
