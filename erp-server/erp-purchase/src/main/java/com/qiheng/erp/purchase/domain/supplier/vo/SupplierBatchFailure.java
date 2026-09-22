package com.qiheng.erp.purchase.domain.supplier.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 供应商批量操作失败明细。
 * <p>替换此前 Map<String,String>,避免 LinkedHashMap.toString() 拼接到 result msg
 * 时前端无法解析的问题;前端可直接渲染失败列表。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "供应商批量操作失败明细")
public class SupplierBatchFailure {

    @Schema(description = "失败的供应商 ID")
    private String supplierId;

    @Schema(description = "失败原因")
    private String reason;
}