package com.qiheng.erp.ai.job;

import com.qiheng.erp.ai.config.AiModelEnabledCondition;
import com.qiheng.erp.ai.skill.GroupSkillRegistry;
import org.springframework.context.annotation.Conditional;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 定时检查技能目录，文件变化时重新加载。
 *
 * <p>只比较文件修改时间和大小，内容未变时不做任何重建。
 * 间隔由 {@code erp.ai.skills.refresh-ms} 控制。</p>
 *
 * @author Li
 * @since 2026-11-22
 */
@Component
@Conditional(AiModelEnabledCondition.class)
public class SkillRefreshJob {

    private final List<GroupSkillRegistry> registries;

    /**
     * 绑定全部技能注册表。
     *
     * @param registries 各分组的技能注册表
     */
    public SkillRefreshJob(List<GroupSkillRegistry> registries) {
        this.registries = registries;
    }

    /** 检查并重新加载发生变化的技能分组。 */
    @Scheduled(fixedDelayString = "${erp.ai.skills.refresh-ms:30000}")
    public void refresh() {
        registries.forEach(GroupSkillRegistry::refreshIfChanged);
    }
}