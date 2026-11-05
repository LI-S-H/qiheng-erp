package com.qiheng.erp.ai.mapper;

import com.github.yulichang.base.MPJBaseMapper;
import com.qiheng.erp.ai.domain.entity.AiDataset;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/** 数据集元信息的持久化；发布由数据库唯一约束保证幂等。 */
public interface AiDatasetMapper extends MPJBaseMapper<AiDataset> {
    /** 只发布当前用户与会话的暂存结果。 */
    @Update("UPDATE ai_dataset SET status='READY', ready_run_id=sql_run_id, data_description_json=#{description} WHERE dataset_id=#{id} AND user_id=#{userId} AND conversation_id=#{conversationId} AND status='STAGED'")
    int approve(@Param("id") String id, @Param("userId") long userId, @Param("conversationId") long conversationId,
                @Param("description") String description);
}
