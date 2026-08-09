package com.qiheng.erp.sales.domain.customer.entity;

import java.io.Serial;
import java.math.BigDecimal;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.IdType;
import java.time.LocalDateTime;
import com.baomidou.mybatisplus.annotation.TableId;
import java.io.Serializable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

/**
 * <p>
 * 客户表
 * </p>
 *
 * @author Li
 * @since 2026-08-10
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("customer")
@Schema(name="Customer对象", description="客户表")
public class Customer implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "客户ID")
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    @Schema(description = "客户编码")
    @TableField("customer_code")
    private String customerCode;

    @Schema(description = "客户名称")
    @TableField("customer_name")
    private String customerName;

    @Schema(description = "联系人")
    @TableField("contact_name")
    private String contactName;

    @Schema(description = "联系电话")
    @TableField("contact_phone")
    private String contactPhone;

    @Schema(description = "地址")
    @TableField("address")
    private String address;

    @Schema(description = "信用额度")
    @TableField("credit_limit")
    private BigDecimal creditLimit;

    @Schema(description = "状态：1启用，0禁用")
    @TableField("status")
    private Integer status;

    @Schema(description = "创建时间")
    @TableField("create_time")
    private LocalDateTime createTime;

    @Schema(description = "更新时间")
    @TableField("update_time")
    private LocalDateTime updateTime;

    @Schema(description = "最后维护人ID")
    @TableField("updated_by_id")
    private Long updatedById;

    @Schema(description = "最后维护人姓名")
    @TableField("updated_by_name")
    private String updatedByName;

    @Schema(description = "逻辑删除：0正常，1删除")
    @TableField("deleted")
    private Integer deleted;

    @Schema(description = "备注")
    @TableField("remark")
    private String remark;

    @Schema(description = "乐观锁版本号")
    @TableField("version")
    private Integer version;


}
