package com.qiheng.erp.ai.memory.node;

import com.qiheng.erp.ai.domain.entity.AiNodeMemory;
import com.qiheng.erp.ai.domain.entity.AiNodeWorkingMemory;
import com.qiheng.erp.ai.mapper.AiNodeMemoryMapper;
import com.qiheng.erp.ai.mapper.AiNodeWorkingMemoryMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

/**
 * 节点记忆统一读写入口。
 *
 * <p>两张表分工明确：原文表保留不可变流水用于审计与追溯，内容是结构化事件 JSON；
 * 工作记忆表是节点读取历史的唯一入口，内容是「模型视角的纯文本转写」，
 * 一行 = 模型的一条消息。压缩只改写工作记忆表，原文表永不改动，
 * 因此压缩出错时可以回到原文重新压缩。</p>
 *
 * <p>节点代码只依赖本类，不直接使用 Mapper。</p>
 *
 * @author Li
 * @since 2026-10-15
 */
@Component
public class NodeMemoryStore {

    private final AiNodeMemoryMapper nodeMemoryMapper;

    private final AiNodeWorkingMemoryMapper workingMemoryMapper;

    public NodeMemoryStore(AiNodeMemoryMapper nodeMemoryMapper,
                           AiNodeWorkingMemoryMapper workingMemoryMapper) {
        this.nodeMemoryMapper = nodeMemoryMapper;
        this.workingMemoryMapper = workingMemoryMapper;
    }

    /**
     * 追加一条流水记忆。
     *
     * <p>只写原文表：内容是结构化事件 JSON，供审计与追溯，不参与节点读取。</p>
     *
     * <p>序号取历史最大值加一，包含逻辑删除的记录，避免唯一索引冲突。</p>
     *
     * @param conversationId 会话 ID
     * @param userId         归属用户
     * @param nodeCode       节点编码，见 {@code NodeCode}
     * @param eventJson      事件 JSON；条目类别以其中的 eventType 字段为准
     */
    @Transactional(rollbackFor = Exception.class)
    public void appendEvent(Long conversationId, Long userId, String nodeCode, String eventJson) {
        AiNodeMemory memory = new AiNodeMemory()
                .setConversationId(conversationId)
                .setUserId(userId)
                .setNodeCode(nodeCode)
                .setSeq(nodeMemoryMapper.selectMaxSeq(conversationId, nodeCode) + 1)
                .setContent(eventJson);
        nodeMemoryMapper.insert(memory);
    }

    /**
     * 追加一条模型视角的记忆：流水表留事件，工作记忆表留纯文本转写。
     *
     * <p>工作记忆行的正文就是模型实际收到或返回的内容，来源引用指向同一条流水行，
     * 压缩时据此回溯被摘要掉的原文。</p>
     *
     * @param conversationId 会话 ID
     * @param userId         归属用户
     * @param nodeCode       节点编码，见 {@code NodeCode}
     * @param eventJson      流水记忆内容，结构化事件 JSON
     * @param transcriptText 工作记忆内容，纯文本转写
     */
    @Transactional(rollbackFor = Exception.class)
    public void appendTranscript(Long conversationId, Long userId, String nodeCode,
                                 String eventJson, String transcriptText) {
        AiNodeMemory memory = new AiNodeMemory()
                .setConversationId(conversationId)
                .setUserId(userId)
                .setNodeCode(nodeCode)
                .setSeq(nodeMemoryMapper.selectMaxSeq(conversationId, nodeCode) + 1)
                .setContent(eventJson);
        nodeMemoryMapper.insert(memory);

        long nextSortOrder = workingMemoryMapper.selectMaxSortOrder(conversationId, nodeCode) + 1;
        AiNodeWorkingMemory workingMemory = new AiNodeWorkingMemory()
                .setConversationId(conversationId)
                .setUserId(userId)
                .setNodeCode(nodeCode)
                .setSortOrder(nextSortOrder)
                .setContent(transcriptText)
                .setSourceMemoryIds("[" + memory.getId() + "]");
        workingMemoryMapper.insert(workingMemory);
    }

    /**
     * 读取节点工作记忆。
     *
     * <p>返回结果已按排序号升序，早期条目可能是压缩摘要块，
     * 调用方拿到什么就用什么，不需要判断压缩状态。行正文是纯文本转写，
     * 解析规则见 {@link WorkingMemoryTranscript}。</p>
     *
     * @param conversationId 会话 ID
     * @param nodeCode       节点编码
     * @return 节点工作记忆，无记录时返回空列表
     */
    public List<AiNodeWorkingMemory> read(Long conversationId, String nodeCode) {
        return workingMemoryMapper.selectWorkingMemory(conversationId, nodeCode);
    }

    public List<AiNodeWorkingMemory> read(Long conversationId, Long userId, String nodeCode) {
        return workingMemoryMapper.selectOwnedWorkingMemory(conversationId, userId, nodeCode);
    }

    /**
     * 只替换工作窗口，原文表不修改；事务失败会同时回滚软删除和新窗口写入。
     * 调用者在会话执行锁内执行，本方法不再获取节点线程无法重入的执行锁。
     */
    @Transactional(rollbackFor = Exception.class)
    public void replaceWorkingMemory(Long conversationId, Long userId, String nodeCode,
                                     List<AiNodeWorkingMemory> previous,
                                     List<AiNodeWorkingMemory> replacements) {
        if (previous.isEmpty() || replacements.isEmpty()) {
            return;
        }
        if (previous.stream().anyMatch(row -> row.getId() == null
                || !Objects.equals(conversationId, row.getConversationId())
                || !Objects.equals(userId, row.getUserId())
                || !Objects.equals(nodeCode, row.getNodeCode()))) {
            throw new IllegalArgumentException("工作记忆窗口归属不匹配");
        }

        long nextOrder = workingMemoryMapper.selectMaxSortOrder(conversationId, nodeCode) + 1;
        int affected = workingMemoryMapper.softDeleteWindow(conversationId, userId, nodeCode,
                previous.stream().map(AiNodeWorkingMemory::getId).toList());
        if (affected != previous.size()) {
            throw new IllegalStateException("工作记忆窗口已经变化，保留原窗口");
        }
        for (AiNodeWorkingMemory replacement : replacements) {
            // 软删行仍占用唯一索引，新窗口从历史最大序号之后开始。
            AiNodeWorkingMemory row = new AiNodeWorkingMemory()
                    .setConversationId(conversationId)
                    .setUserId(userId)
                    .setNodeCode(nodeCode)
                    .setSortOrder(nextOrder++)
                    .setContent(replacement.getContent())
                    .setSourceMemoryIds(replacement.getSourceMemoryIds());
            workingMemoryMapper.insert(row);
        }
    }
}
