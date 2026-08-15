package com.qiheng.erp.dashboard.domain.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * <p>
 * 工作台经营概览响应 VO
 * </p>
 *
 * <p>字段顺序与 OpenAPI {@code DashboardOverview} 一致；后端按当前用户权限裁剪，
 * 无权模块的指标、待办、列表返回空数组或 0 值，不泄露无权业务数据。</p>
 *
 * @author Li
 * @since 2026-08-15
 */
@Data
@Schema(description = "工作台经营概览响应")
public class DashboardOverviewVO {

    @Schema(description = "工作台数据刷新时间，后端生成")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime refreshedAt;

    @Schema(description = "首屏经营指标，按当前用户权限裁剪")
    private List<DashboardMetricVO> metrics = new ArrayList<>();

    @Schema(description = "近 30 日经营趋势，销售额、采购额和毛利额均来自可执行聚合查询")
    private List<DashboardTrendPointVO> trend = new ArrayList<>();

    @Schema(description = "当前用户可见的全部工作台待办，业务完成后自动消失")
    private List<DashboardTodoItemVO> todos = new ArrayList<>();

    @Schema(description = "库存风险 SKU 完整数组")
    private List<DashboardStockAlertVO> stockAlerts = new ArrayList<>();

    @Schema(description = "采购单和销售单状态分布")
    private List<DashboardOrderStageVO> orderStages = new ArrayList<>();

    @Schema(description = "近 30 日销售商品排行完整数组，按 salesAmount 降序返回")
    private List<DashboardTopProductVO> topProducts = new ArrayList<>();

    @Schema(description = "供应商履约评分完整数组，按 overallScore 降序返回")
    private List<DashboardSupplierPerformanceVO> supplierPerformance = new ArrayList<>();
}