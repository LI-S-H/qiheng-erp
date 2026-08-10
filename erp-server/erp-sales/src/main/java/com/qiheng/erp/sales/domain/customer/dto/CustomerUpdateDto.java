package com.qiheng.erp.sales.domain.customer.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

/**
 * <p>
 * 编辑客户请求 DTO
 * </p>
 *
 * @author Li
 * @since 2026-08-10
 */
@Data
@Schema(description = "编辑客户请求")
public class CustomerUpdateDto {

    @NotBlank(message = "客户名称不能为空")
    @Size(max = 200, message = "客户名称最长200个字符")
    @Schema(description = "客户名称")
    private String customerName;

    @NotBlank(message = "联系人不能为空")
    @Size(max = 100, message = "联系人最长100个字符")
    @Schema(description = "联系人")
    private String contactName;

    @NotBlank(message = "联系电话不能为空")
    @Size(max = 32, message = "联系电话最长32个字符")
    @Schema(description = "联系电话")
    private String contactPhone;

    @NotBlank(message = "地址不能为空")
    @Size(max = 255, message = "地址最长255个字符")
    @Schema(description = "地址")
    private String address;

    @NotNull(message = "信用额度不能为空")
    @DecimalMin(value = "0", message = "信用额度不能小于0")
    @Schema(description = "信用额度")
    private BigDecimal creditLimit;

    @NotNull(message = "状态不能为空")
    @Min(value = 0, message = "状态值不合法")
    @Max(value = 1, message = "状态值不合法")
    @Schema(description = "状态：1启用，0禁用")
    private Integer status;

    @NotBlank(message = "备注不能为空")
    @Size(max = 500, message = "备注最长500个字符")
    @Schema(description = "备注")
    private String remark;

    @NotNull(message = "版本号不能为空")
    @Min(value = 0, message = "版本号不能小于0")
    @Schema(description = "当前 customer.version,乐观锁校验")
    private Integer version;
}