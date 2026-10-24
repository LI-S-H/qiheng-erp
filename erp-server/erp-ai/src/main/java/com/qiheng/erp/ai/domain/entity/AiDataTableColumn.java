package com.qiheng.erp.ai.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.qiheng.erp.common.handler.JsonStringListTypeHandler;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * <p>
 * AI 可用数据表列登记表
 * </p>
 *
 * <p>与表级语义不同：这里的登记表是表的<b>完整结构跟踪</b>，库里的每一列都登记，
 * {@code status} 决定这一列是否下发给模型。{@code status=0} 的典型是乐观锁、备注、
 * 人员操作痕迹——它们结构上存在，但对分析无意义。</p>
 *
 * <p>结构字段（{@code columnType}、{@code nullable}、{@code primaryKey}、{@code uniqueKey}）
 * 可由同步程序从 information_schema 刷新；语义字段（{@code businessName}、
 * {@code description}、{@code enumValues}）只能人工维护，同步程序一律不覆盖。</p>
 *
 * @author Li
 * @since 2026-10-21
 */
@Data
@Accessors(chain = true)
@TableName(value = "ai_data_table_column", autoResultMap = true)
@Schema(name = "AiDataTableColumn", description = "AI 可用数据表列登记表")
public class AiDataTableColumn implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "主键 ID")
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    @Schema(description = "物理表名")
    @TableField("table_name")
    private String tableName;

    @Schema(description = "物理列名")
    @TableField("column_name")
    private String columnName;

    @Schema(description = "列类型，结构字段可自动同步")
    @TableField("column_type")
    private String columnType;

    @Schema(description = "是否可空，结构字段可自动同步")
    @TableField("nullable")
    private Integer nullable;

    @Schema(description = "是否主键，结构字段可自动同步")
    @TableField("primary_key")
    private Integer primaryKey;

    @Schema(description = "是否唯一键，结构字段可自动同步")
    @TableField("unique_key")
    private Integer uniqueKey;

    @Schema(description = "业务名称，人工维护")
    @TableField("business_name")
    private String businessName;

    @Schema(description = "口径说明：单位、放大倍数、取值含义，人工维护")
    @TableField("description")
    private String description;

    @Schema(description = "枚举样例，人工维护；空数组表示非枚举")
    @TableField(value = "enum_values", typeHandler = JsonStringListTypeHandler.class)
    private List<String> enumValues;

    @Schema(description = "开关：1 下发给模型，0 不下发")
    @TableField("status")
    private Integer status;

    @Schema(description = "列顺序")
    @TableField("sort_order")
    private Integer sortOrder;

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
