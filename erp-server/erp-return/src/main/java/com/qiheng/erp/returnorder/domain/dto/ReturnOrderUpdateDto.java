package com.qiheng.erp.returnorder.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * <p>
 * 编辑统一退货单草稿请求 DTO
 * </p>
 *
 * @author Li
 * @since 2026-07-31
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "编辑统一退货单草稿请求")
public class ReturnOrderUpdateDto extends ReturnOrderBaseDto {

    @NotNull(message = "版本号不能为空")
    @Min(value = 0, message = "版本号不能小于0")
    @Schema(description = "乐观锁版本号")
    private Integer version;
}
