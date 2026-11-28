package com.qiheng.erp.ai.experience;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiheng.erp.ai.agent.context.TokenEstimator;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 分智能体整理全部有效经验；模型处理期间不持有数据库事务。 */
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
    private final TransactionTemplate transactions;

    /**
     * 创建经验整理服务。
     *
     * @param experiences 经验存储
     * @param summaries 目录摘要存储
     * @param client 模型客户端
     * @param provider 摘要缓存
     * @param properties 经验配置
     * @param mapper JSON 组件
     * @param transactionManager 事务管理器
     * @throws IOException 提示词读取失败
     */
    public ExperienceMergeService(AiExperienceMapper experiences, AiExperienceSummaryMapper summaries,
                                  ExperienceModelClient client, ExperienceProvider provider,
                                  AiExperienceProperties properties, ObjectMapper mapper,
                                  PlatformTransactionManager transactionManager) throws IOException {
        this.experiences = experiences;
        this.summaries = summaries;
        this.client = client;
        this.provider = provider;
        this.properties = properties;
        this.mapper = mapper;
        this.prompt = new ClassPathResource("prompts/experience-merge.md")
                .getContentAsString(StandardCharsets.UTF_8);
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /** 分别合并主智能体和分析智能体，单类失败不影响另一类。 */
    public void mergeAll() {
        if (!properties.enabled()) {
            return;
        }
        for (String agentType : List.of("MAIN", "ANALYSIS")) {
            try {
                mergeOne(agentType);
            } catch (RuntimeException error) {
                log.error("经验合并失败，保留原数据 agentType={} errorType={}",
                        agentType, error.getClass().getName());
            }
        }
    }

    /** 分页准备输入，所有阶段通过后才以短事务替换。 */
    private void mergeOne(String agentType) {
        int pageSize = properties.mergePageSize();
        if (pageSize <= 0 || properties.mergeInputMaxTokens() <= 0) {
            throw new IllegalStateException("经验合并分页和输入预算必须大于零");
        }
        Map<Long, String> snapshot = new LinkedHashMap<>();
        List<String> pending = new ArrayList<>();
        MergeResult stage = null;
        Long afterId = null;
        while (true) {
            List<AiExperience> page = experiences.selectPublishedPage(agentType, afterId, pageSize);
            if (page == null || page.isEmpty()) {
                break;
            }
            for (AiExperience item : page) {
                if (item.getId() == null || item.getExperienceText() == null
                        || afterId != null && item.getId() <= afterId) {
                    throw new IllegalStateException("经验分页排序或数据无效");
                }
                afterId = item.getId();
                snapshot.put(item.getId(), digest(item.getExperienceText()));
                List<String> candidate = combine(stage, pending, item.getExperienceText());
                if (!fits(candidate)) {
                    if (pending.isEmpty()) {
                        throw new IllegalStateException("单条经验与阶段结果超出输入预算");
                    }
                    stage = mergeChunk(combine(stage, pending, null));
                    pending.clear();
                    if (!fits(combine(stage, pending, item.getExperienceText()))) {
                        throw new IllegalStateException("阶段结果与下一条经验超出输入预算");
                    }
                }
                pending.add(item.getExperienceText());
            }
        }
        if (snapshot.isEmpty()) {
            return;
        }
        if (!pending.isEmpty()) {
            stage = mergeChunk(combine(stage, pending, null));
        }
        if (stage == null) {
            throw new IllegalStateException("经验合并没有结果");
        }
        MergeResult result = stage;
        transactions.executeWithoutResult(status -> replace(agentType, snapshot, result));
        provider.invalidate(agentType);
        log.info("经验合并完成 agentType={} existing={} merged={}",
                agentType, snapshot.size(), result.entries().size());
    }

    /** 阶段结果和当前批次一起输入，避免漏掉跨批重复。 */
    private List<String> combine(MergeResult stage, List<String> pending, String next) {
        List<String> values = new ArrayList<>();
        if (stage != null) {
            for (MergeEntry entry : stage.entries()) {
                values.add(entry.text());
            }
        }
        values.addAll(pending);
        if (next != null) {
            values.add(next);
        }
        return values;
    }

    /** 估算系统提示和 JSON 的整体输入，输出预算独立配置。 */
    private boolean fits(List<String> values) {
        return TokenEstimator.estimate(prompt) + TokenEstimator.estimate(buildInput(values)) + 16
                <= properties.mergeInputMaxTokens();
    }

    /** 模型输出必须整批有效，不能过滤部分后删除旧经验。 */
    private MergeResult mergeChunk(List<String> values) {
        MergeResult result = client.merge(prompt, buildInput(values));
        if (result == null || result.entries() == null || result.entries().isEmpty()
                || result.summary() == null || result.summary().isBlank()) {
            throw new IllegalStateException("经验合并输出为空或无效");
        }
        for (MergeEntry entry : result.entries()) {
            if (entry == null || entry.text() == null || entry.text().isBlank()
                    || entry.text().length() > properties.maxTextChars()) {
                throw new IllegalStateException("经验合并输出存在无效或超长条目");
            }
        }
        return result;
    }

    /** 原经验只作为数据输入，不要求模型维护数据库编号。 */
    private String buildInput(List<String> values) {
        try {
            List<Map<String, String>> entries = values.stream().map(value -> Map.of("text", value)).toList();
            return mapper.writeValueAsString(Map.of("experiences", entries));
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("经验合并输入序列化失败", error);
        }
    }

    /** 事务内锁定并核对全量快照，再按输入编号替换。 */
    private void replace(String agentType, Map<Long, String> snapshot, MergeResult result) {
        Long cursor = null;
        int lockedCount = 0;
        while (true) {
            List<AiExperience> locked = experiences.selectPublishedForUpdatePage(agentType, cursor, properties.mergePageSize());
            if (locked == null) {
                throw new IllegalStateException("经验快照锁定查询失败");
            }
            if (locked.isEmpty()) {
                break;
            }
            for (AiExperience item : locked) {
                if (item.getId() == null || item.getExperienceText() == null
                        || cursor != null && item.getId() <= cursor
                        || !digest(item.getExperienceText()).equals(snapshot.get(item.getId()))) {
                    throw new IllegalStateException("经验合并期间原数据已变化");
                }
                cursor = item.getId();
                lockedCount++;
            }
        }
        if (lockedCount != snapshot.size()) {
            throw new IllegalStateException("经验合并期间原数据数量已变化");
        }
        List<Long> ids = new ArrayList<>(snapshot.keySet());
        int batchSize = properties.mergePageSize();
        for (int offset = 0; offset < ids.size(); offset += batchSize) {
            List<Long> batch = ids.subList(offset, Math.min(offset + batchSize, ids.size()));
            if (experiences.deletePublishedByIds(agentType, batch) != batch.size()) {
                throw new IllegalStateException("经验删除数量与快照不一致");
            }
        }
        LocalDateTime now = LocalDateTime.now();
        List<AiExperience> records = result.entries().stream().map(entry -> new AiExperience()
                .setId(IdWorker.getId()).setAgentType(agentType).setExperienceText(entry.text().strip())
                .setStatus("PUBLISHED").setEffectiveFrom(now).setCreateTime(now).setUpdateTime(now)
                .setDeleted(0)).toList();
        for (int offset = 0; offset < records.size(); offset += batchSize) {
            List<AiExperience> batch = records.subList(offset, Math.min(offset + batchSize, records.size()));
            if (experiences.insertPublishedBatch(batch) != batch.size()) {
                throw new IllegalStateException("经验批量插入数量不一致");
            }
        }
        String summary = result.summary().strip();
        if (summary.length() > properties.summaryMaxChars()) {
            summary = summary.substring(0, properties.summaryMaxChars());
        }
        summaries.upsert(agentType, summary);
    }

    /** 快照只保存正文摘要和编号，避免持有全量经验正文。 */
    private String digest(String text) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("缺少 SHA-256 实现", error);
        }
    }
}
