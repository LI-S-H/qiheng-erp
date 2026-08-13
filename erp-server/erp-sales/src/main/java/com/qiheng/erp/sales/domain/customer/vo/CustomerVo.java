package com.qiheng.erp.sales.domain.customer.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.qiheng.erp.common.config.MoneyStringSerializer;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * <p>
 * 客户分页查询响应 VO
 * </p>
 *
 * @author Li
 * @since 2026-08-10
 */
@Data
@Schema(description = "客户分页查询响应")
public class CustomerVo {

    @Schema(description = "客户ID")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long customerId;

    @Schema(description = "客户编码")
    private String customerCode;

    @Schema(description = "客户名称")
    private String customerName;

    @Schema(description = "联系人")
    private String contactName;

    @Schema(description = "联系电话")
    private String contactPhone;

    @Schema(description = "地址")
    private String address;

    @Schema(description = "信用额度")
    @JsonSerialize(using = MoneyStringSerializer.class)
    private BigDecimal creditLimit;

    @Schema(description = "状态：1启用，0禁用")
    private Integer status;

    @Schema(description = "乐观锁版本号")
    private Integer version;

    @Schema(description = "备注")
    private String remark;

    @Schema(description = "创建时间")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;

    @Schema(description = "更新时间")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updateTime;

    @Schema(description = "最后维护人ID")
    @JsonSerialize(using = ToStringSerializer.class)
    private long updatedById;

    @Schema(description = "最后维护人姓名")
    private String updatedByName;
}
