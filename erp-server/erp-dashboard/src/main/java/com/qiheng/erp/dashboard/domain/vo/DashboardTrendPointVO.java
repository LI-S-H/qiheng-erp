package com.qiheng.erp.dashboard.domain.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.qiheng.erp.dashboard.config.DashboardMoneySerializer;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * <p>
 * 工作台经营趋势点 VO
 * </p>
 *
 * <p>前端在工作台卡片内提供 7 天、15 天、30 天切换，只做本地截取、x 轴标签降密和轻量过渡，
 * 不额外调用详情接口。</p>
 *
 * @author Li
 * @since 2026-08-15
 */
@Data
@Schema(description = "工作台经营趋势点")
public class DashboardTrendPointVO {

    @Schema(description = "日期，格式 yyyy-MM-dd")
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate date;

    @Schema(description = "当日销售净值（人民币元，最多两位小数金额字符串；负数表示退货多于销售）")
    @JsonSerialize(using = DashboardMoneySerializer.class)
    private BigDecimal salesAmount;

    @Schema(description = "当日采购净值（人民币元，最多两位小数金额字符串；负数表示退货多于采购）")
    @JsonSerialize(using = DashboardMoneySerializer.class)
    private BigDecimal purchaseAmount;

    @Schema(description = "当日毛利额（人民币元，最多两位小数金额字符串；负数表示亏损）")
    @JsonSerialize(using = DashboardMoneySerializer.class)
    private BigDecimal grossMarginAmount;
}