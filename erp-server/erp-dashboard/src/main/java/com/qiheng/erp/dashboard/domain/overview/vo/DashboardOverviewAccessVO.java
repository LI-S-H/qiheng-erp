package com.qiheng.erp.dashboard.domain.overview.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/** 工作台各指标、各业务面板的访问状态集合。 */
@Data
@Schema(description = "工作台各指标和业务面板的访问状态")
public class DashboardOverviewAccessVO {

    @Schema(description = "按指标 key 索引的访问状态")
    private Map<String, DashboardSectionAccessVO> metrics = new LinkedHashMap<>();

    private DashboardSectionAccessVO todos;
    private DashboardSectionAccessVO stockAlerts;
    private DashboardSectionAccessVO orderStages;
    private DashboardSectionAccessVO topProducts;
    private DashboardSectionAccessVO supplierPerformance;
}