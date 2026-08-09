package com.qiheng.erp.returnorder.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 统一退货单原因操作请求（取消等场景使用）。
 *
 * @author Li
 * @since 2026-08-08
 */
@Data
@Schema(description = "统一退货单原因操作请求")
public class ReturnOrderReasonActionRequest {

    @NotNull(message = "版本号不能为空")
    @Min(value = 0, message = "版本号不能小于0")
    @Schema(description = "乐观锁版本号")
    private Integer version;

    @NotBlank(message = "原因不能为空")
    @Size(max = 500, message = "原因最长500个字符")
    @Schema(description = "原因，写入 return_order.status_reason")
    private String reason;
}
