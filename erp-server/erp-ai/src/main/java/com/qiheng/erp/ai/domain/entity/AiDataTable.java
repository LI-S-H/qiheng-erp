package com.qiheng.erp.ai.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.qiheng.erp.ai.domain.model.IndexDefinition;
import com.qiheng.erp.ai.handler.JsonIndexDefinitionListTypeHandler;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * <p>
 * AI 可用数据表登记表
 * </p>
 *
 * <p>受控取数底座的表清单。登记表本身就是授权范围：AI 只能查这里登记的表，
 * 不登记就接触不到。因此本表没有 enabled 开关，{@code status} 承担全部开关职责。</p>
 *
 * <p>字段分三层：结构字段（{@code indexDefs}）来自 information_schema 的事实，
 * 可由同步程序刷新；语义字段（{@code businessName}、{@code description}）是库表结构里
 * 没有的业务知识，只能人工维护，同步程序一律不覆盖。</p>
 *
 * @author Li
 * @since 2026-10-21
 */
@Data
@Accessors(chain = true)
@TableName(value = "ai_data_table", autoResultMap = true)
@Schema(name = "AiDataTable", description = "AI 可用数据表登记表")
public class AiDataTable implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "主键 ID")
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    @Schema(description = "物理表名")
    @TableField("table_name")
    private String tableName;

    @Schema(description = "业务名称，给模型看")
    @TableField("business_name")
    private String businessName;

    @Schema(description = "所属模块编码，对应 ERP 权限码前缀")
    @TableField("module_code")
    private String moduleCode;

    @Schema(description = "表说明与口径总述，给模型看")
    @TableField("description")
    private String description;

    @Schema(description = "父表（明细表指向主表），非空表示该表是明细表")
    @TableField("parent_table")
    private String parentTable;

    @Schema(description = "索引定义，结构字段可自动同步")
    @TableField(value = "index_defs", typeHandler = JsonIndexDefinitionListTypeHandler.class)
    private List<IndexDefinition> indexDefs;

    @Schema(description = "Schema 组装顺序，主表在前")
    @TableField("sort_order")
    private Integer sortOrder;

    @Schema(description = "开关：1 生效，0 停用；人工下线某张表时改这里")
    @TableField("status")
    private Integer status;

    @Schema(description = "创建时间")
    @TableField("create_time")
    private LocalDateTime createTime;

    @Schema(description = "更新时间")
    @TableField("update_time")
    private LocalDateTime updateTime;

    @Schema(description = "逻辑删除：0 未删除，1 已删除")
    @TableLogic
    @TableField("deleted")
    private Integer deleted;

    @Schema(description = "乐观锁版本号")
    @Version
    @TableField("version")
    private Integer version;
}
