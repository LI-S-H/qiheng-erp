package com.qiheng.erp.purchase.domain.supplier.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 基础资料编辑请求，不含任何评分字段。 */
@Data
@Schema(description = "编辑供应商请求")
public class SupplierUpdateDto {

    @Schema(description = "当前供应商版本号，用于乐观锁校验")
    @NotNull
    @DecimalMin("0")
    private Integer version;

    @Schema(description = "供应商名称，1-200 字符")
    @NotBlank
    @Size(max = 200)
    private String supplierName;

    @Schema(description = "联系人；选填，未维护时服务端以空字符串保存并返回")
    @Size(max = 100)
    private String contactName;

    @Schema(description = "联系电话；选填，未维护时服务端以空字符串保存并返回")
    @Size(max = 32)
    private String contactPhone;

    @Schema(description = "地址；选填，未维护时服务端以空字符串保存并返回")
    @Size(max = 255)
    private String address;

    @Schema(description = "付款条件；选填，未维护时服务端以空字符串保存并返回")
    @Size(max = 100)
    private String paymentTerms;

    @Schema(description = "状态：1 启用，0 停用")
    @NotNull
    @DecimalMin("0")
    @DecimalMax("1")
    private Integer status;

    @Schema(description = "备注")
    @Size(max = 500)
    private String remark;
}