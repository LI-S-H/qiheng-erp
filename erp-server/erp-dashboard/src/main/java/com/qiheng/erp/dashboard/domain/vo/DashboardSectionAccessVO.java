package com.qiheng.erp.dashboard.domain.vo;

import com.qiheng.erp.dashboard.domain.enums.DashboardAccessState;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/** 工作台单个指标或区块的访问状态。 */
@Data
@Schema(description = "工作台指标或区块的访问状态")
public class DashboardSectionAccessVO {

    @Schema(description = "状态：ALLOWED / EMPTY / DENIED", allowableValues = {"ALLOWED", "EMPTY", "DENIED"})
    private DashboardAccessState state;

    public static DashboardSectionAccessVO of(DashboardAccessState state) {
        DashboardSectionAccessVO access = new DashboardSectionAccessVO();
        access.setState(state);
        return access;
    }
}