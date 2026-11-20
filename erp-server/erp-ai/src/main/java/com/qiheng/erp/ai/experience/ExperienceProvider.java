package com.qiheng.erp.ai.experience;

import com.qiheng.erp.ai.config.AiModelEnabledCondition;
import com.qiheng.erp.ai.config.properties.AiExperienceProperties;
import com.qiheng.erp.ai.mapper.AiExperienceSummaryMapper;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 经验目录摘要读取：按智能体缓存，避免每轮模型调用都查库。
 *
 * <p>合并写入后立即失效，下一次对话读取新摘要。</p>
 *
 * @author Li
 * @since 2026-11-20
 */
@Component
@Conditional(AiModelEnabledCondition.class)
public final class ExperienceProvider {

    private final AiExperienceSummaryMapper summaries;
    private final AiExperienceProperties properties;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    /**
     * 注入摘要存储与缓存配置。
     *
     * @param summaries 摘要存储
     * @param properties 经验配置
     */
    public ExperienceProvider(AiExperienceSummaryMapper summaries, AiExperienceProperties properties) {
        this.summaries = summaries;
        this.properties = properties;
    }

    /**
     * 读取某个智能体的经验目录摘要。
     *
     * @param agentType 智能体类型
     * @return 摘要文本；没有时返回 null
     */
    public String summary(String agentType) {
        long now = System.currentTimeMillis();
        Cached cached = cache.get(agentType);
        if (cached != null && cached.expiresAt() > now) {
            return cached.value();
        }
        String value = summaries.selectSummary(agentType);
        cache.put(agentType, new Cached(value, now + properties.cacheSeconds() * 1000));
        return value;
    }

    /**
     * 合并后清除缓存，让新摘要立即生效。
     *
     * @param agentType 智能体类型
     */
    public void invalidate(String agentType) {
        cache.remove(agentType);
    }

    /** 缓存项。 */
    private record Cached(String value, long expiresAt) {
    }
}