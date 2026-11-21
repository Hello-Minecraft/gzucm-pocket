package com.gzucm.pocket.core

import com.gzucm.pocket.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/**
 * 班级日程云端的 HTTP 客户端（契约 §2）。
 *
 * ## 和天气共用同一个云函数
 *
 * 域名和 `X-App-Token` 都从 `BuildConfig.WEATHER_BASE` / `WEATHER_APP_TOKEN` 读 ——
 * **同一个 FC 实例、同一个 APP_TOKEN**，只是路径前缀换成 `/classplan`。
 * 不另开一套配置：多一个域名就多一份要维护的密钥，而且用户只需要配一次。
 * 配了 `weather.properties` 里的 `base` + `app_token`，天气和班级日程一起生效；
 * 没配（比如别人 clone 下来构建）就两个功能都安静地不出现，不会报错。
 *
 * ## 为什么不用 [Net.client]
 *
 * [Net.client] 挂着学校的 cookie（教务、一卡通）。把那些 cookie 发给自己的
 * FC 没有任何用处，还多一份「万一 FC 日志被打出来」的泄露面。这里用独立的
 * client：无 cookieJar、超时短、失败就当失败，不和教务那一套互相影响。
 *
 * ## 失败一律不抛异常
 *
 * 所有方法都返回 [Result]，断网 / 超时 / 非 JSON / 业务错误码都在这里翻译成
 * [Reason]，UI 只决定「要不要提示、提示什么」，不需要写 try/catch ——
 * 首页每天自动同步一次，抛异常会直接把整个页面搞崩。
 */
class ClassSyncApi(
    private val base: String = BuildConfig.WEATHER_BASE,
    private val appToken: String = BuildConfig.WEATHER_APP_TOKEN,
    private val client: OkHttpClient = DefaultClient,
) {

    /** 没配 FC 地址时整块功能不出现，而不是每次都失败一次 */
    val enabled: Boolean get() = base.isNotBlank()

    sealed class Result<out T> {
        data class Ok<out T>(val value: T) : Result<T>()

        /**
         * @param reason 机器可判的原因，调用方按它决定要不要提示
         * @param message 给用户看的一句话（服务端给了中文就用服务端的）
         */
        data class Failed(val reason: Reason, val message: String = "") : Result<Nothing>()
    }

    enum class Reason {
        /** 没配 FC 地址 */
        NOT_CONFIGURED,
        /** 断网 / DNS 失败 / 连接被拒 */
        OFFLINE,
        /** 连上了但超时 */
        TIMEOUT,
        /** X-App-Token 不对（服务端 `bad_token`，或 HTTP 401/403） */
        BAD_TOKEN,
        /** 清单不存在（HTTP 404 / `not_found`）—— 通常是学委删了整份 */
        NOT_FOUND,
        /** 参数校验没过：`bad_class` / `bad_key` / `bad_count` */
        BAD_REQUEST,
        /** 这个人在这班已经有 2 份了 */
        QUOTA_USER,
        /** 这个班已经有 8 份了 */
        QUOTA_CLASS,
        /** 删别人的清单（`not_owner`） */
        NOT_OWNER,
        /** 服务端 5xx */
        SERVER,
        /** 返回的不是可解析的 JSON，或者 JSON 结构对不上 */
        BAD_RESPONSE,
    }

    // ── 四个接口 ──────────────────────────────────────────────────

    /** `POST /classplan/index`：本班清单摘要。拿不到就当「没有清单」，不弹窗 */
    suspend fun index(className: String): Result<ClassPlanIndex> = withContext(Dispatchers.IO) {
        val body = buildJsonObject { put("class", className) }
        when (val r = postJson("/classplan/index", body)) {
            is Result.Failed -> r
            is Result.Ok -> {
                val obj = r.value
                /*
                 * plans 解析失败只丢摘要，不整条失败：服务端换了字段名时，
                 * 用户至少还能看到「本班有 N 份」里的 N 从别的键算出来 ——
                 * 但这里没有别的键，所以退化成空列表，等价于「暂时没清单」。
                 */
                val plans = obj["plans"]?.let { el ->
                    runCatching {
                        Net.json.decodeFromString(ListSerializer(ClassPlanBrief.serializer()), el.toString())
                    }.getOrNull()
                } ?: emptyList()
                Result.Ok(
                    ClassPlanIndex(
                        v = obj["v"]?.jsonPrimitive?.intOrNull ?: 1,
                        updatedAt = obj["updatedAt"]?.jsonPrimitive?.longOrNull ?: 0L,
                        plans = plans,
                    ),
                )
            }
        }
    }

    /** `POST /classplan/get`：一份清单正文，原样透传（服务端不改写任何字段） */
    suspend fun plan(className: String, id: String): Result<ClassPlan> = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("class", className)
            put("id", id)
        }
        when (val r = postJson("/classplan/get", body)) {
            is Result.Failed -> r
            is Result.Ok -> {
                val element = r.value["plan"]
                    ?: return@withContext Result.Failed(Reason.BAD_RESPONSE, "服务端没返回清单正文")
                val plan = runCatching {
                    Net.json.decodeFromString(ClassPlan.serializer(), element.toString())
                }.getOrNull()
                    ?: return@withContext Result.Failed(Reason.BAD_RESPONSE, "清单格式对不上")
                Result.Ok(plan)
            }
        }
    }

    /** `POST /classplan/publish`：发布 / 覆盖自己那份 */
    suspend fun publish(
        className: String,
        uploader: String,
        plan: ClassPlan,
    ): Result<ClassPublishAck> = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("class", className)
            put("uploader", uploader)
            put("plan", Net.json.encodeToJsonElement(ClassPlan.serializer(), plan))
        }
        when (val r = postJson("/classplan/publish", body)) {
            is Result.Failed -> r
            is Result.Ok -> {
                val obj = r.value
                val id = obj["id"]?.jsonPrimitive?.contentOrNull.orEmpty()
                if (id.isBlank()) {
                    Result.Failed(Reason.BAD_RESPONSE, "发布成功但没拿到清单 id")
                } else {
                    Result.Ok(
                        ClassPublishAck(
                            id = id,
                            updatedAt = obj["updatedAt"]?.jsonPrimitive?.longOrNull ?: 0L,
                        ),
                    )
                }
            }
        }
    }

    /** `POST /classplan/delete`：只能删自己发的（服务端比对 maintainer） */
    suspend fun delete(
        className: String,
        id: String,
        uploader: String,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("class", className)
            put("id", id)
            put("uploader", uploader)
        }
        when (val r = postJson("/classplan/delete", body)) {
            is Result.Failed -> r
            is Result.Ok -> Result.Ok(Unit)
        }
    }

    // ── 传输 ──────────────────────────────────────────────────────

    /**
     * 统一 POST。
     *
     * 契约要求全部用 POST（GET 也能走，但省得处理 querystring 编码）。
     * 返回值统一是「响应体里的 JSON 对象」，`ok != true` 已经翻译成 [Result.Failed]。
     */
    private fun postJson(path: String, body: JsonObject): Result<JsonObject> {
        if (!enabled) {
            return Result.Failed(Reason.NOT_CONFIGURED, "班级日程服务还没配置")
        }
        val request = Request.Builder()
            .url(base.trimEnd('/') + path)
            .post(body.toString().toRequestBody(JsonBody))
            .header("Content-Type", "application/json")
            .apply {
                // 和天气同一个 token；FC 里留空时不校验，这时也没必要带
                if (appToken.isNotBlank()) header("X-App-Token", appToken)
            }
            .build()

        val resp = try {
            client.newCall(request).execute()
        } catch (e: SocketTimeoutException) {
            return Result.Failed(Reason.TIMEOUT, "连接班级日程服务超时，稍后再试")
        } catch (e: IOException) {
            return Result.Failed(Reason.OFFLINE, "网络不通，稍后再试")
        }

        resp.use { r ->
            val text = r.body?.string().orEmpty()
            /*
             * 非 JSON 一律当 BAD_RESPONSE：FC 挂了会返回 HTML 错误页、
             * 网关超时会返回纯文本，这两种都被当成「服务端故障」比
             * 抛一个 SerializationException 上去好得多。
             */
            val obj = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull()
                ?: return Result.Failed(
                    if (r.isSuccessful) Reason.BAD_RESPONSE else reasonOfStatus(r.code),
                    "服务返回的不是 JSON（HTTP ${r.code}）",
                )

            val code = obj["error"]?.jsonPrimitive?.contentOrNull
            val msg = obj["message"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

            if (!r.isSuccessful) {
                return Result.Failed(reasonOfCode(code, r.code), msg ?: "服务器返回 ${r.code}")
            }
            if (obj["ok"]?.jsonPrimitive?.booleanOrNull != true) {
                /*
                 * 200 但 ok=false：契约里只有 not_found 会出现这种情况，
                 * 其它错误码都配 4xx。reasonOfCode 认不出 code 时按 HTTP 状态兜底，
                 * 状态 200 会落到 BAD_RESPONSE —— 「服务器说不行但没说为什么」
                 * 这种状态，报「格式对不上」最贴近事实。
                 */
                return Result.Failed(reasonOfCode(code, r.code), msg ?: "服务端拒绝了这次请求")
            }
            return Result.Ok(obj)
        }
    }

    private fun reasonOfCode(code: String?, status: Int): Reason = when (code) {
        "bad_token" -> Reason.BAD_TOKEN
        "bad_class", "bad_key", "bad_count" -> Reason.BAD_REQUEST
        "quota_user" -> Reason.QUOTA_USER
        "quota_class" -> Reason.QUOTA_CLASS
        "not_owner" -> Reason.NOT_OWNER
        "not_found" -> Reason.NOT_FOUND
        else -> reasonOfStatus(status)
    }

    private fun reasonOfStatus(code: Int): Reason = when {
        code == 404 -> Reason.NOT_FOUND
        code == 401 || code == 403 -> Reason.BAD_TOKEN
        code in 500..599 -> Reason.SERVER
        code in 400..499 -> Reason.BAD_REQUEST
        else -> Reason.BAD_RESPONSE
    }

    private companion object {
        val JsonBody = "application/json; charset=utf-8".toMediaType()

        /**
         * 超时比教务那套短：同步是后台行为，卡住 20 秒不如早点失败，
         * 反正 24 小时后还会再来一次。
         */
        val DefaultClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .writeTimeout(20, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()
        }
    }
}
