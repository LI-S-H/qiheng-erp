package com.qiheng.erp.dashboard.domain.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * <p>
 * 工作台顶栏通知铃铛摘要 VO
 * </p>
 *
 * <p>对应 OpenAPI {@code /dashboard/notifications}，承载顶栏弹层数据，避免全局主布局频繁请求较重的 {@code /dashboard/overview}。</p>
 *
 * @author Li
 * @since 2026-08-15
 */
@Data
@Schema(description = "工作台顶栏通知铃铛摘要")
public class DashboardNotificationPopoverVO {

    @Schema(description = "通知数据刷新时间，后端生成")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime refreshedAt;

    @Schema(description = "权限裁剪和业务去重后的待处理业务事项总量")
    private Integer pendingCount;

    @Schema(description = "与 pendingCount 相同口径下 priority=HIGH 的待处理事项总量")
    private Integer highPriorityCount;

    @Schema(description = "排序后的待办是否超过弹层返回的前 8 条；为 true 时前端引导进入工作台查看全部")
    private Boolean hasMore;

    @Schema(description = "当前用户可见且状态为 PENDING 的前 8 条待办，已按后端权重排序")
    private List<DashboardTodoItemVO> items = new ArrayList<>();
}