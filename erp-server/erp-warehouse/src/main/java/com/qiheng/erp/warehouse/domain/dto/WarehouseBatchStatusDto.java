package com.qiheng.erp.warehouse.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
@Schema(description = "仓库批量更新状态请求")
public class WarehouseBatchStatusDto {
    @NotNull(message = "仓库ID列表不能为空")
    @Size(min = 1, message = "仓库ID列表不能为空")
    @Schema(description = "仓库ID列表")
    private List<String> warehouseIds;

    @NotNull(message = "版本号映射不能为空")
    @Schema(description = "仓库ID -> 版本号映射")
    private Map<String, Integer> versionByWarehouseId;

    @NotNull(message = "状态不能为空")
    @Min(value = 0, message = "状态不能小于0")
    @Max(value = 1, message = "状态不能大于1")
    @Schema(description = "状态：1启用，0禁用")
    private Integer status;
}