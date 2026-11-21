package com.qiheng.erp.ai.skill;

import com.alibaba.cloud.ai.graph.agent.interceptor.ModelCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelInterceptor;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelResponse;
import com.alibaba.cloud.ai.graph.skills.SkillMetadata;
import com.alibaba.cloud.ai.graph.skills.registry.SkillRegistry;
import org.springframework.ai.chat.messages.SystemMessage;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 每轮模型调用前注入可用技能清单。
 *
 * <p>只输出技能名和说明，不输出支持文件的绝对路径说明——本系统的支持文件统一通过
 * {@code readSkillFile} 按技能目录内的相对路径读取。清单追加到本次请求的系统消息，
 * 不写入会话历史。</p>
 *
 * @author Li
 * @since 2026-11-21
 */
public final class SkillIndexInterceptor extends ModelInterceptor {

    private final SkillRegistry registry;

    /**
     * 绑定技能注册表。
     *
     * @param registry 当前智能体的技能注册表
     */
    public SkillIndexInterceptor(SkillRegistry registry) {
        this.registry = registry;
    }

    /**
     * 把技能清单追加到系统消息。
     *
     * @param request 本次模型请求
     * @param handler 后续调用链
     * @return 模型响应
     */
    @Override
    public ModelResponse interceptModel(ModelRequest request, ModelCallHandler handler) {
        List<SkillMetadata> skills = registry.listAll();
        if (skills.isEmpty()) {
            return handler.call(request);
        }
        String list = skills.stream()
                .map(skill -> "- **" + skill.getName() + "**: " + skill.getDescription())
                .collect(Collectors.joining("\n"));
        String instruction = request.getSystemMessage() == null ? "" : request.getSystemMessage().getText();
        return handler.call(ModelRequest.builder(request)
                .systemMessage(new SystemMessage(instruction + "\n\n" + SkillPrompt.TEMPLATE.replace("{skills_list}", list)))
                .build());
    }

    /**
     * 返回拦截器名称。
     *
     * @return 名称
     */
    @Override
    public String getName() {
        return "skill_index";
    }
}