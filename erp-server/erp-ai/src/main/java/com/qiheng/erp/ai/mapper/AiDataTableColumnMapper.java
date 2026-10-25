package com.qiheng.erp.ai.mapper;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.github.yulichang.base.MPJBaseMapper;
import com.qiheng.erp.ai.domain.entity.AiDataTableColumn;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * <p>
 * AI 可用数据表列登记表 Mapper 接口
 * </p>
 *
 * <p>查询一律走 MyBatis-Plus 的 Wrapper：实体声明了 {@code autoResultMap}，
 * 枚举列的 typeHandler 才会随结果映射生效。自定义 {@code @Select} 不套用
 * {@code @TableField} 的 typeHandler，枚举列会读成 null。</p>
 *
 * @author Li
 * @since 2026-10-21
 */
public interface AiDataTableColumnMapper extends MPJBaseMapper<AiDataTableColumn> {

    /**
     * 取指定表集合里「要下发给模型」的列。
     *
     * <p>{@code status = 1} 挡掉噪声列与敏感列。</p>
     */
    default List<AiDataTableColumn> selectEnabledByTables(List<String> tables) {
        return selectList(Wrappers.<AiDataTableColumn>lambdaQuery()
                .in(AiDataTableColumn::getTableName, tables)
                .eq(AiDataTableColumn::getStatus, 1)
                .orderByAsc(AiDataTableColumn::getTableName)
                .orderByAsc(AiDataTableColumn::getSortOrder));
    }

    /**
     * 取指定表集合的全部未软删登记，含 {@code status=0} 的列。
     *
     * <p>同步程序用：噪声列与被人工屏蔽的列仍要跟踪结构变化，
     * 否则它们解除屏蔽时登记已经过期。</p>
     */
    default List<AiDataTableColumn> selectByTables(List<String> tables) {
        return selectList(Wrappers.<AiDataTableColumn>lambdaQuery()
                .in(AiDataTableColumn::getTableName, tables)
                .orderByAsc(AiDataTableColumn::getTableName)
                .orderByAsc(AiDataTableColumn::getColumnName));
    }

    /**
     * 取指定表集合里某一列的登记，含 {@code status=0} 的列。
     *
     * <p>用于判断"这张表有没有软删除列"——这是表结构事实，与该列是否下发给模型无关：
     * 软删除列即使被屏蔽，查询不带过滤条件也照样会读到已删除的行。</p>
     */
    default List<AiDataTableColumn> selectByTablesAndColumn(List<String> tables, String columnName) {
        return selectList(Wrappers.<AiDataTableColumn>lambdaQuery()
                .in(AiDataTableColumn::getTableName, tables)
                .eq(AiDataTableColumn::getColumnName, columnName));
    }

    /**
     * 取指定表集合的主键列，含 {@code status=0} 的列。
     *
     * <p>主键是表结构事实：翻页游标要靠它定位，即使这一列被人工屏蔽，
     * 代码也知道它是主键（模型仍然看不到、也查不了这一列）。</p>
     */
    default List<AiDataTableColumn> selectPrimaryKeys(List<String> tables) {
        return selectList(Wrappers.<AiDataTableColumn>lambdaQuery()
                .in(AiDataTableColumn::getTableName, tables)
                .eq(AiDataTableColumn::getPrimaryKey, 1));
    }

    /**
     * 查一个列的登记，含已软删的行。
     *
     * <p>只取标识与开关字段，不涉及 JSON 列，因此不受自定义查询的 typeHandler 限制。
     * 用于「列被删掉又加回来」时复活旧登记，而不是撞唯一键。</p>
     */
    @Select("""
        SELECT id, table_name, column_name, status, deleted
        FROM ai_data_table_column
        WHERE table_name = #{tableName} AND column_name = #{columnName}
        """)
    @Options(timeout = 30)
    AiDataTableColumn selectIdentityIncludingDeleted(@Param("tableName") String tableName,
                                                     @Param("columnName") String columnName);

    /**
     * 同步程序用：只刷新结构字段，语义字段一个字都不动。
     */
    @Update("""
        UPDATE ai_data_table_column
        SET column_type = #{columnType},
            nullable = #{nullable},
            primary_key = #{primaryKey},
            unique_key = #{uniqueKey},
            version = version + 1
        WHERE id = #{id} AND deleted = 0
        """)
    @Options(timeout = 30)
    int updateStructure(@Param("id") Long id,
                        @Param("columnType") String columnType,
                        @Param("nullable") Integer nullable,
                        @Param("primaryKey") Integer primaryKey,
                        @Param("uniqueKey") Integer uniqueKey);

    /**
     * 同步程序用：列在库里被删掉时软删登记，保留历史不物理删除。
     */
    @Update("""
        UPDATE ai_data_table_column
        SET deleted = 1, version = version + 1
        WHERE id = #{id} AND deleted = 0
        """)
    @Options(timeout = 30)
    int softDelete(@Param("id") Long id);

    /**
     * 复活一条已软删的登记：列被删掉又加回来时复用旧行，避免撞唯一键。
     */
    @Update("""
        UPDATE ai_data_table_column
        SET deleted = 0,
            column_type = #{columnType},
            nullable = #{nullable},
            primary_key = #{primaryKey},
            unique_key = #{uniqueKey},
            status = #{status},
            version = version + 1
        WHERE id = #{id}
        """)
    @Options(timeout = 30)
    int revive(@Param("id") Long id,
               @Param("columnType") String columnType,
               @Param("nullable") Integer nullable,
               @Param("primaryKey") Integer primaryKey,
               @Param("uniqueKey") Integer uniqueKey,
               @Param("status") Integer status);
}
