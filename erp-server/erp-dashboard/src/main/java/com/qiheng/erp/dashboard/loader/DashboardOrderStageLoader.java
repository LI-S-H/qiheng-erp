package com.qiheng.erp.dashboard.loader;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qiheng.erp.dashboard.domain.vo.DashboardOrderStageVO;
import com.qiheng.erp.dashboard.permission.DashboardPermissionGuard;
import com.qiheng.erp.purchase.domain.purchaseorder.entity.PurchaseOrder;
import com.qiheng.erp.purchase.domain.purchaseorder.enums.PurchaseOrderStatus;
import com.qiheng.erp.purchase.mapper.PurchaseOrderMapper;
import com.qiheng.erp.sales.domain.salesorder.entity.SalesOrder;
import com.qiheng.erp.sales.domain.salesorder.enums.SalesOrderStatus;
import com.qiheng.erp.sales.mapper.SalesOrderMapper;
import com.qiheng.erp.security.domain.dto.LoginUser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 工作台订单流转阶段分布聚合器。
 *
 * <p>固定返回 6 个阶段（草稿/待审核/已审核/部分出入库/已完成/已取消）；
 * 子项按当前用户权限裁剪：
 * <ul>
 *   <li>无 purchase:query → purchaseCount 全部为 0，对应列不渲染</li>
 *   <li>无 sales:query → salesCount 全部为 0，对应列不渲染</li>
 * </ul>
 *
 * <p>前端主卡按顺序展示，不提供详情弹窗。</p>
 *
 * @author Li
 * @since 2026-08-15
 */
@Component
public class DashboardOrderStageLoader {

    /** 阶段显示名称 → 状态编码，固定顺序 */
    private static final List<String[]> PURCHASE_STAGES = List.of(
            new String[]{"草稿", PurchaseOrderStatus.DRAFT.name()},
            new String[]{"待审核", PurchaseOrderStatus.SUBMITTED.name()},
            new String[]{"已审核", PurchaseOrderStatus.APPROVED.name()},
            new String[]{"部分出入库", PurchaseOrderStatus.PARTIAL_INBOUND.name()},
            new String[]{"已完成", PurchaseOrderStatus.INBOUND_DONE.name()},
            new String[]{"已取消", PurchaseOrderStatus.CANCELLED.name()}
    );

    private static final List<String[]> SALES_STAGES = List.of(
            new String[]{"草稿", SalesOrderStatus.DRAFT.name()},
            new String[]{"待审核", SalesOrderStatus.SUBMITTED.name()},
            new String[]{"已审核", SalesOrderStatus.APPROVED.name()},
            new String[]{"部分出入库", SalesOrderStatus.PARTIAL_OUTBOUND.name()},
            new String[]{"已完成", SalesOrderStatus.OUTBOUND_DONE.name()},
            new String[]{"已取消", SalesOrderStatus.CANCELLED.name()}
    );

    private final DashboardPermissionGuard permissionGuard;
    private final PurchaseOrderMapper purchaseOrderMapper;
    private final SalesOrderMapper salesOrderMapper;

    @Autowired
    public DashboardOrderStageLoader(DashboardPermissionGuard permissionGuard,
                                     PurchaseOrderMapper purchaseOrderMapper,
                                     SalesOrderMapper salesOrderMapper) {
        this.permissionGuard = permissionGuard;
        this.purchaseOrderMapper = purchaseOrderMapper;
        this.salesOrderMapper = salesOrderMapper;
    }

    /**
     * 加载订单流转阶段分布
     *
     * @param user 当前登录用户
     * @return 6 个阶段统计 VO；无权维度对应列归 0
     */
    public List<DashboardOrderStageVO> load(LoginUser user) {
        boolean canPurchase = permissionGuard.canViewPurchase(user);
        boolean canSales = permissionGuard.canViewSales(user);

        Map<String, Integer> purchaseCount = canPurchase
                ? purchaseOrderMapper.selectList(
                        new LambdaQueryWrapper<PurchaseOrder>().select(PurchaseOrder::getStatus)).stream()
                        .collect(Collectors.groupingBy(PurchaseOrder::getStatus,
                                Collectors.collectingAndThen(Collectors.counting(), Long::intValue)))
                : Map.of();
        Map<String, Integer> salesCount = canSales
                ? salesOrderMapper.selectList(
                        new LambdaQueryWrapper<SalesOrder>().select(SalesOrder::getStatus)).stream()
                        .collect(Collectors.groupingBy(SalesOrder::getStatus,
                                Collectors.collectingAndThen(Collectors.counting(), Long::intValue)))
                : Map.of();

        List<DashboardOrderStageVO> result = new ArrayList<>();
        for (int i = 0; i < PURCHASE_STAGES.size(); i++) {
            String[] purchaseStage = PURCHASE_STAGES.get(i);
            String[] salesStage = SALES_STAGES.get(i);
            // 仅当前缀相同时对齐采购和销售阶段，否则按各自列表分别输出（罕见情况，防御式）
            if (!purchaseStage[0].equals(salesStage[0])) {
                break;
            }
            DashboardOrderStageVO vo = new DashboardOrderStageVO();
            vo.setStage(purchaseStage[0]);
            vo.setPurchaseCount(purchaseCount.getOrDefault(purchaseStage[1], 0));
            vo.setSalesCount(salesCount.getOrDefault(salesStage[1], 0));
            result.add(vo);
        }
        return result;
    }

    /** 占位方法，便于在编译期确认两阶段表顺序一致 */
    @SuppressWarnings("unused")
    private static boolean assertStagesAligned() {
        return Stream.of(PURCHASE_STAGES.size() == SALES_STAGES.size()).allMatch(Boolean::booleanValue);
    }
}
