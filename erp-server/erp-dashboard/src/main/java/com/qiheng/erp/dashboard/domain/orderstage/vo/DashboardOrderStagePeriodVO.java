package com.qiheng.erp.dashboard.domain.orderstage.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.qiheng.erp.dashboard.domain.orderstage.enums.DashboardOrderStagePeriodType;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 工作台订单流转统计期间。
 *
 * <p>显式返回实际查询边界，前端据此展示"当月"口径，避免用页面文案猜测后台统计范围。</p>
 */
@Data
@Schema(description = "工作台订单流转统计期间")
public class DashboardOrderStagePeriodVO {

    @Schema(description = "统计期间类型")
    private DashboardOrderStagePeriodType type;

    @Schema(description = "统计起始时间（含）")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime startAt;

    @Schema(description = "统计结束时间（不含）")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime endAtExclusive;
}