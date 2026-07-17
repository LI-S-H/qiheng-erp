package com.qiheng.erp.warehouse.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
@Schema(description = "仓库状态更新请求")
public class WarehouseStatusDto {
    @NotNull(message = "状态不能为空")
    @Min(value = 0, message = "状态不能小于0")
    @Max(value = 1, message = "状态不能大于1")
    @Schema(description = "状态：1启用，0禁用")
    private Integer status;

    @NotNull(message = "版本号不能为空")
    @Schema(description = "乐观锁版本号")
    private Integer version;
}