package com.qiheng.erp.ai.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.openai.api.ResponseFormat;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.client.reactive.JdkClientHttpConnector;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;

/**
 * OpenAI 兼容端点的模型装配。
 *
 * <p>集中处理两类网关差异，业务侧只给 base-url / key / 模型名：</p>
 * <ul>
 *   <li><b>宽松的响应解析</b>：部分网关会把 {@code finish_reason} 的 null 序列化成字符串
 *   {@code "null"}，Spring AI 的目标枚举不认识这个值，整条响应会解析失败。这里用
 *   {@code READ_UNKNOWN_ENUM_VALUES_AS_NULL} 把无法识别的枚举当 null，坏字段不至于毁掉整次调用。</li>
 *   <li><b>不偷偷重试</b>：传输层只尝试一次，失败明确暴露给上层，由应用决定是否重试，
 *   避免把预算和错误分类搅在一起。</li>
 * </ul>
 *
 * @author Li
 * @since 2026-10-24
 */
public final class OpenAiCompatibleChatModel {

    private OpenAiCompatibleChatModel() {
    }

    /**
     * 建一个非流式模型。
     *
     * @param apiKey      密钥
     * @param baseUrl     兼容端点基础地址（不含 {@code /v1/chat/completions}，由框架自己拼）
     * @param model       模型名
     * @param temperature 采样温度
     * @param readTimeout    单次响应读取超时
     * @param enableThinking 是否允许模型先思考；null 表示不传这个参数（非通义系模型不认它）
     * @return 模型
     */
    public static ChatModel create(String apiKey, String baseUrl, String model, Double temperature,
                                   Duration readTimeout, Boolean enableThinking) {
        var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        var requests = new JdkClientHttpRequestFactory(http);
        requests.setReadTimeout(readTimeout);
        var streamingHttp = new JdkClientHttpConnector(http);
        streamingHttp.setReadTimeout(readTimeout);

        // 无法识别的枚举值（如字符串 "null"）当 null 处理，别让一个字段毁掉整条响应
        var lenient = JsonMapper.builder()
                .enable(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_AS_NULL)
                .build();
        RestClient.Builder rest = RestClient.builder()
                .requestFactory(requests)
                .messageConverters(converters -> {
                    converters.removeIf(converter -> converter instanceof MappingJackson2HttpMessageConverter);
                    converters.add(new MappingJackson2HttpMessageConverter(lenient));
                });

        OpenAiChatOptions.Builder options = OpenAiChatOptions.builder().model(model).temperature(temperature)
                // 强制 JSON：模型偶尔会把分析过程当正文输出，导致解析失败
                .responseFormat(ResponseFormat.builder().type(ResponseFormat.Type.JSON_OBJECT).build());
        if (enableThinking != null) {
            // 通义系模型默认会先长时间推理；关掉后同样任务的耗时从分钟级降到十几秒
            options.extraBody(Map.of("enable_thinking", enableThinking));
        }
        return OpenAiChatModel.builder()
                .openAiApi(OpenAiApi.builder().baseUrl(baseUrl).apiKey(apiKey)
                        .restClientBuilder(rest)
                        .webClientBuilder(WebClient.builder().clientConnector(streamingHttp))
                        .build())
                .retryTemplate(RetryTemplate.builder().maxAttempts(1).build())
                .defaultOptions(options.build())
                .build();
    }
}