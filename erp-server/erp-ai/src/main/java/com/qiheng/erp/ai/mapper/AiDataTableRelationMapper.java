package com.qiheng.erp.ai.mapper;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.github.yulichang.base.MPJBaseMapper;
import com.qiheng.erp.ai.domain.entity.AiDataTableRelation;
import org.apache.ibatis.annotations.Options;

import java.util.List;

/**
 * <p>
 * AI 可用数据表关系登记表 Mapper 接口
 * </p>
 *
 * @author Li
 * @since 2026-10-21
 */
public interface AiDataTableRelationMapper extends MPJBaseMapper<AiDataTableRelation> {

    /**
     * 取全部启用的关系。
     *
     * <p>「两端表都可见」这个过滤放在组装层做：关系表本身不知道当前用户能看到什么，
     * 那属于组装期的可见性判断，不属于登记数据。</p>
     */
    default List<AiDataTableRelation> selectEnabled() {
        return selectList(Wrappers.<AiDataTableRelation>lambdaQuery()
                .eq(AiDataTableRelation::getStatus, 1)
                .orderByAsc(AiDataTableRelation::getFromTable)
                .orderByAsc(AiDataTableRelation::getFromColumn)
                .orderByAsc(AiDataTableRelation::getToTable));
    }

    /**
     * 同步程序用：取全部未软删的关系登记。
     *
     * <p>用于校验关系登记的列是否还真实存在——列被删了，关系就失效了。</p>
     */
    default List<AiDataTableRelation> selectAllRegistered() {
        return selectList(Wrappers.<AiDataTableRelation>lambdaQuery()
                .orderByAsc(AiDataTableRelation::getFromTable)
                .orderByAsc(AiDataTableRelation::getFromColumn));
    }
}
