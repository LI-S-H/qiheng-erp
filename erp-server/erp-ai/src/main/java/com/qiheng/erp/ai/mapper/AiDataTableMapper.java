package com.qiheng.erp.ai.mapper;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.github.yulichang.base.MPJBaseMapper;
import com.qiheng.erp.ai.domain.entity.AiDataTable;
import com.qiheng.erp.ai.domain.model.IndexDefinition;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * <p>
 * AI 可用数据表登记表 Mapper 接口
 * </p>
 *
 * <p>查询一律走 MyBatis-Plus 的 Wrapper：实体声明了 {@code autoResultMap}，
 * JSON 列的 typeHandler 才会随结果映射生效。自定义 {@code @Select} 不套用
 * {@code @TableField} 的 typeHandler，JSON 列会读成 null。</p>
 *
 * <p>逻辑删除由 {@code @TableLogic} 自动过滤，查询看不到已软删的登记。</p>
 *
 * @author Li
 * @since 2026-10-21
 */
public interface AiDataTableMapper extends MPJBaseMapper<AiDataTable> {

    /**
     * 按模块取已启用的表：模块来自用户权限码的前缀。
     *
     * <p>模块集合为空表示该用户没有任何可查模块，调用方直接返回空结果，
     * 不走这条查询——空集合拼出的 {@code IN ()} 不是合法 SQL。</p>
     */
    default List<AiDataTable> selectEnabledByModules(List<String> modules) {
        return selectList(Wrappers.<AiDataTable>lambdaQuery()
                .in(AiDataTable::getModuleCode, modules)
                .eq(AiDataTable::getStatus, 1)
                .orderByAsc(AiDataTable::getSortOrder));
    }

    /**
     * 取全部启用的表，供超级管理员用：权限码是 {@code ["*"]} 时不受模块限制。
     */
    default List<AiDataTable> selectEnabledAll() {
        return selectList(Wrappers.<AiDataTable>lambdaQuery()
                .eq(AiDataTable::getStatus, 1)
                .orderByAsc(AiDataTable::getSortOrder));
    }

    /**
     * 取全部未软删的登记，含 {@code status=0} 的表。
     *
     * <p>同步程序用：{@code status=0} 的表是人工下线过的，仍需跟踪其结构变化，
     * 否则重新启用时登记已经过期。</p>
     */
    default List<AiDataTable> selectAllRegistered() {
        return selectList(Wrappers.<AiDataTable>lambdaQuery().orderByAsc(AiDataTable::getTableName));
    }

    /**
     * 同步程序用：只刷新索引定义这个结构字段。
     *
     * <p>语义字段一个字都不动——它们是人工维护的，同步程序不碰。</p>
     */
    @Update("""
        UPDATE ai_data_table
        SET index_defs = #{indexDefs, typeHandler=com.qiheng.erp.ai.handler.JsonIndexDefinitionListTypeHandler},
            version = version + 1
        WHERE id = #{id} AND deleted = 0
        """)
    @Options(timeout = 30)
    void updateIndexDefs(@Param("id") Long id,
                        @Param("indexDefs") List<IndexDefinition> indexDefs);
}
