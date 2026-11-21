package com.qiheng.erp.ai.experience;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiheng.erp.ai.config.AiModelEnabledCondition;
import com.qiheng.erp.ai.config.properties.AiExperienceProperties;
import com.qiheng.erp.ai.domain.entity.AiExperience;
import com.qiheng.erp.ai.experience.ExperienceModelClient.MergeEntry;
import com.qiheng.erp.ai.experience.ExperienceModelClient.MergeResult;
import com.qiheng.erp.ai.mapper.AiExperienceMapper;
import com.qiheng.erp.ai.mapper.AiExperienceSummaryMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 合并同一个智能体的经验：模型给回新的完整列表，程序按 id 落库。
 *
 * <p>合并只做整理，不做发布判断；任何失败都回滚，保留提取阶段写入的原始条目。</p>
 *
 * @author Li
 * @since 2026-11-20
 */
@Component
@Conditional(AiModelEnabledCondition.class)
public class ExperienceMergeService {

    private static final Logger log = LoggerFactory.getLogger(ExperienceMergeService.class);

    private final AiExperienceMapper experiences;
    private final AiExperienceSummaryMapper summaries;
    private final ExperienceModelClient client;
    private final ExperienceProvider provider;
    private final AiExperienceProperties properties;
    private final ObjectMapper mapper;
    private final String prompt;

    /**
     * 加载合并提示词。
     *
     * @param experiences 经验存储
     * @param summaries 摘要存储
     * @param client 模型客户端
     * @param provider 摘要缓存
     * @param properties 经验配置
     * @param mapper JSON 组件
     * @throws IOException 提示词读取失败
     */
    public ExperienceMergeService(AiExperienceMapper experiences, AiExperienceSummaryMapper summaries,
                                  ExperienceModelClient client, ExperienceProvider provider,
                                  AiExperienceProperties properties, ObjectMapper mapper) throws IOException {
        this.experiences = experiences;
        this.summaries = summaries;
        this.client = client;
        this.provider = provider;
        this.properties = properties;
        this.mapper = mapper;
        this.prompt = new ClassPathResource("prompts/experience-merge.md")
                .getContentAsString(StandardCharsets.UTF_8);
    }

    /** 分别合并主智能体和分析智能体的经验。 */
    @Transactional
    public void mergeAll() {
        if (!properties.enabled()) {
            return;
        }
        mergeOne("MAIN");
        mergeOne("ANALYSIS");
    }

    /** 合并一个智能体的经验；没有经验或模型失败时保持原样。 */
    private void mergeOne(String agentType) {
        List<AiExperience> existing = experiences.selectPublished(
                agentType, properties.mergeMaxExperiences());
        if (existing.isEmpty()) {
            return;
        }
        String input = buildInput(existing);
        MergeResult result = client.merge(prompt, input);
        if (result == null) {
            log.warn("经验合并失败，保留原数据 agentType={} existing={}", agentType, existing.size());
            return;
        }
        Set<Long> existingIds = new HashSet<>();
        for (AiExperience item : existing) {
            existingIds.add(item.getId());
        }
        List<MergeEntry> entries = validate(agentType, result.entries(), existingIds);
        if (entries.isEmpty()) {
            log.warn("经验合并输出为空，保留原数据 agentType={}", agentType);
            return;
        }
        save(agentType, existingIds, entries, lastSourceTaskId(existing));
        summaries.upsert(agentType, limit(result.summary(), properties.summaryMaxChars()));
        provider.invalidate(agentType);
        log.info("经验合并完成 agentType={} existing={} merged={}",
                agentType, existing.size(), entries.size());
    }

    /** 把现有经验序列化成合并输入。 */
    private String buildInput(List<AiExperience> existing) {
        List<Map<String, Object>> items = new ArrayList<>(existing.size());
        for (AiExperience item : existing) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("id", item.getId());
            value.put("text", item.getExperienceText());
            value.put("sourceTaskId", item.getSourceTaskId());
            items.add(value);
        }
        try {
            return mapper.writeValueAsString(Map.of("existing", items));
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("经验合并输入序列化失败", error);
        }
    }

    /** 校验合并输出：id 必须来自输入，长度和重复项直接过滤。 */
    private List<MergeEntry> validate(String agentType, List<MergeEntry> entries, Set<Long> existingIds) {
        List<MergeEntry> result = new ArrayList<>();
        Set<Long> seenIds = new HashSet<>();
        Set<String> seenTexts = new HashSet<>();
        for (MergeEntry entry : entries) {
            String text = entry.text() == null ? "" : entry.text().strip();
            if (text.isBlank()) {
                continue;
            }
            if (text.length() > properties.maxTextChars()) {
                log.warn("合并经验超过 {} 字，丢弃 agentType={} length={}",
                        properties.maxTextChars(), agentType, text.length());
                continue;
            }
            if (entry.id() != null && !existingIds.contains(entry.id())) {
                log.warn("合并经验使用了不存在的 id，丢弃 agentType={} id={}", agentType, entry.id());
                continue;
            }
            if (entry.id() != null && !seenIds.add(entry.id())) {
                continue;
            }
            if (!seenTexts.add(text)) {
                continue;
            }
            result.add(new MergeEntry(entry.id(), text));
        }
        return result;
    }

    /** 按输出落库：更新、插入、软删除。 */
    private void save(String agentType, Set<Long> existingIds, List<MergeEntry> entries,
                      String fallbackSourceTaskId) {
        LocalDateTime now = LocalDateTime.now();
        Set<Long> keptIds = new HashSet<>();
        for (MergeEntry entry : entries) {
            if (entry.id() != null) {
                experiences.updateText(entry.id(), agentType, entry.text());
                keptIds.add(entry.id());
            } else {
                experiences.insert(new AiExperience()
                        .setAgentType(agentType)
                        .setExperienceText(entry.text())
                        .setSourceTaskId(fallbackSourceTaskId)
                        .setStatus("PUBLISHED")
                        .setEffectiveFrom(now)
                        .setCreateTime(now)
                        .setUpdateTime(now)
                        .setDeleted(0));
            }
        }
        for (Long id : existingIds) {
            if (!keptIds.contains(id)) {
                experiences.markDeleted(id, agentType);
            }
        }
    }

    /** 新增条目的来源任务取输入中最近的条目。 */
    private String lastSourceTaskId(List<AiExperience> existing) {
        for (AiExperience item : existing) {
            if (item.getSourceTaskId() != null && !item.getSourceTaskId().isBlank()) {
                return item.getSourceTaskId();
            }
        }
        return null;
    }

    /** 截断摘要到配置长度。 */
    private String limit(String summary, int maxChars) {
        String value = summary == null ? "" : summary.strip();
        return value.length() <= maxChars ? value : value.substring(0, maxChars);
    }
}