package com.qiheng.erp.warehouse.domain.warehouse.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
@Schema(description = "仓库批量删除请求")
public class WarehouseBatchDeleteDto {
    @NotEmpty(message = "仓库ID列表不能为空")
    @Schema(description = "仓库ID列表")
    private List<String> warehouseIds;

    @NotNull(message = "版本号映射不能为空")
    @Schema(description = "仓库ID -> 版本号映射")
    private Map<String, Integer> versionByWarehouseId;
}