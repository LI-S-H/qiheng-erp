package com.qiheng.erp.purchase.domain.supplier.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 单条删除供应商请求 DTO。
 * <p>复用 OptimisticLockVersionRequest 字段约定,显式提供 DTO 以便走 Bean Validation。</p>
 */
@Data
@Schema(description = "删除供应商请求")
public class SupplierDeleteDto {

    @Schema(description = "Current supplier.version for optimistic locking.")
    @NotNull(message = "版本号不能为空")
    @Min(value = 0, message = "版本号不能小于0")
    private Integer version;
}