package com.qiheng.erp.ai.config;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.checkpoint.savers.redis.RedisSaver;
import com.alibaba.cloud.ai.graph.serializer.plain_text.jackson.SpringAIJacksonStateSerializer;
import org.redisson.api.RedissonClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;

/** 直接使用官方 RedisSaver；State 只存 JSON 兼容值，不依赖旧消息 Codec。 */
@Configuration(proxyBeanMethods = false)
@Conditional(AiModelEnabledCondition.class)
public class RedisSaverConfiguration {

    @Bean
    public RedisSaver graphRedisSaver(RedissonClient redis) {
        return RedisSaver.builder()
                .redisson(redis)
                .stateSerializer(new SpringAIJacksonStateSerializer(OverAllState::new))
                .build();
    }
}
