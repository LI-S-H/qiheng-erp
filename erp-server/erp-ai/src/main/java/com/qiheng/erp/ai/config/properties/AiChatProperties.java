package com.qiheng.erp.ai.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 供应商接入与模型选择，对应 {@code erp.ai.*}。
 *
 * <p>原来 {@code api-key}、{@code base-url}、{@code temperature} 在三个模型 Bean 上
 * 各写了一遍，共 9 处重复。收进来之后改网关只改一处。</p>
 *
 * <p>{@code erp.ai.enabled} 刻意不放在这里：它由 {@link com.qiheng.erp.ai.config.AiModelEnabledCondition}
 * 直接从 Environment 读。属性类和开关各留一个来源，避免同一个值维护两遍。</p>
 *
 * @param apiKey      供应商密钥
 * @param baseUrl     兼容网关地址
 * @param temperature 采样温度
 * @param model       模型名与超时
 * @author Li
 * @since 2026-10-26
 */
@ConfigurationProperties(prefix = "erp.ai")
public record AiChatProperties(
        String apiKey,
        @DefaultValue("https://api.deepseek.com") String baseUrl,
        @DefaultValue("0.2") Double temperature,
        Model model) {

    /**
     * {@code erp.ai.model} 整块缺失时补一份默认值。
     *
     * <p>原来的 {@code @Value} 每个键都带默认值，整块缺失也不影响启动；
     * 属性绑定对嵌套对象给的是 null，这里补回来才能保持原行为。</p>
     */
    public AiChatProperties {
        model = model == null ? Model.DEFAULTS : model;
    }

    /**
     * 模型名与读取超时，对应 {@code erp.ai.model.*}。
     *
     * @param pro                   Pro 模型名
     * @param flash                 Flash 模型名
     * @param enableThinking        是否允许先思考；空表示不传该参数
     * @param readTimeoutSeconds    Pro 读取超时（秒）
     * @param flashReadTimeoutSeconds Flash 读取超时（秒）
     */
    public record Model(
            @DefaultValue("deepseek-v4-pro") String pro,
            @DefaultValue("deepseek-flash") String flash,
            String enableThinking,
            @DefaultValue("300") long readTimeoutSeconds,
            @DefaultValue("60") long flashReadTimeoutSeconds) {

        /** 与各组件上的 {@code @DefaultValue} 保持一致，绑定测试会钉住两边不跑偏。 */
        static final Model DEFAULTS =
                new Model("deepseek-v4-pro", "deepseek-flash", null, 300L, 60L);
    }
}