package com.qiheng.erp.common.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 乐观锁版本号请求，用于提交、确认、取消等状态变更操作
 *
 * @author Li
 * @since 2026-07-26
 */
@Data
@Schema(description = "乐观锁版本号请求")
public class OptimisticLockVersionDto {

    @Schema(description = "当前记录版本号，用于乐观锁并发控制")
    @NotNull(message = "版本号不能为空")
    @Min(value = 0, message = "版本号不能为负")
    private Integer version;
}
