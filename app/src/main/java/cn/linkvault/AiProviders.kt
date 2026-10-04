package cn.linkvault

import java.net.URI
import org.json.JSONObject

internal data class AiProvider(val id: String, val name: String, val endpoint: String, val model: String, val console: String, val note: String = "")

/** 官方 OpenAI 兼容接口，使用完整 POST 地址。 */
internal object AiProviders {
    val presets = listOf(
        AiProvider("deepseek", "DeepSeek", "https://api.deepseek.com/chat/completions", "deepseek-flash", "https://platform.deepseek.com/", "在官方平台创建 API Key，API 用量单独计费。"),
        AiProvider("zhipu", "智谱 GLM", "https://open.bigmodel.cn/api/paas/v4/chat/completions", "glm-5.3", "https://bigmodel.cn/", "使用通用 API；编码套餐的专属接口不适用于此阅读助手。"),
        AiProvider("qwen", "通义千问", "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions", "qwen-plus", "https://bailian.console.aliyun.com/", "预设为北京地域兼容地址，请使用北京地域 API Key；也可改成业务空间专属域名。"),
        AiProvider("kimi", "Kimi", "https://api.moonshot.cn/v1/chat/completions", "kimi-k3", "https://platform.kimi.com/", "API 开通与聊天会员分开，实际可用模型以账户权限为准。"),
        AiProvider("siliconflow", "硅基流动", "https://api.siliconflow.cn/v1/chat/completions", "deepseek-ai/DeepSeek-V4-Flash", "https://cloud.siliconflow.cn/", "模型权限与价格以平台为准，可手动修改模型名。"),
        AiProvider("openai", "OpenAI", Translate.DEFAULT_ENDPOINT, Translate.DEFAULT_MODEL, "https://platform.openai.com/"),
        AiProvider("custom", "自定义", "", "", "")
    )
    val default get() = presets.first()
    fun detect(endpoint: String): AiProvider = presets.firstOrNull { it.endpoint == endpoint } ?: presets.last()
    fun fingerprint(config: Translate.Config): String = Analysis.hash("${config.endpoint}\n${config.model}\n${config.apiKey}")

    fun adapt(config: Translate.Config, body: JSONObject): JSONObject {
        val host = runCatching { URI(config.endpoint).host }.getOrNull()
        when (host) {
            "api.deepseek.com", "open.bigmodel.cn" -> body.put("thinking", JSONObject().put("type", "disabled"))
            "dashscope.aliyuncs.com", "api.siliconflow.cn" -> body.put("enable_thinking", false)
            "api.moonshot.cn" -> {
                body.remove("temperature")
                if (config.model.startsWith("kimi-k3")) body.put("reasoning_effort", "low")
            }
        }
        return body
    }
}
