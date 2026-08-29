package com.qiheng.erp.dashboard.loader;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qiheng.erp.common.util.QtyUtil;
import com.qiheng.erp.dashboard.domain.vo.DashboardTrendPointVO;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 工作台经营趋势聚合器。
 *
 * <p>返回近 30 天的销售金额、采购金额和毛利额（销售 - 采购）按天序列。
 * 子项按当前用户权限裁剪：
 * <ul>
 *   <li>无 sales:query → 销售曲线字段为 0</li>
 *   <li>无 purchase:query → 采购曲线字段为 0</li>
 *   <li>毛利仅在销售与采购权限都齐备时计算，否则为 0</li>
 * </ul>
 *
 * <p>前端在工作台卡片内提供 7 天、15 天、30 天切换，只做本地截取，不额外调用接口。</p>
 *
 * @author Li
 * @since 2026-08-15
 */
@Component
public class DashboardTrendLoader {

    private static final int TREND_DAYS = 30;

    private final DashboardPermissionGuard permissionGuard;
    private final SalesOrderMapper salesOrderMapper;
    private final PurchaseOrderMapper purchaseOrderMapper;

    @Autowired
    public DashboardTrendLoader(DashboardPermissionGuard permissionGuard,
                               SalesOrderMapper salesOrderMapper,
                               PurchaseOrderMapper purchaseOrderMapper) {
        this.permissionGuard = permissionGuard;
        this.salesOrderMapper = salesOrderMapper;
        this.purchaseOrderMapper = purchaseOrderMapper;
    }

    /**
     * 加载近 30 天经营趋势
     *
     * @param user 当前登录用户
     * @return 按日期升序的 30 个趋势点；无权维度金额归 0
     */
    public List<DashboardTrendPointVO> load(LoginUser user) {
        LocalDate today = LocalDate.now();
        LocalDate startDate = today.minusDays(TREND_DAYS - 1L);

        boolean canSales = permissionGuard.canViewSales(user);
        boolean canPurchase = permissionGuard.canViewPurchase(user);

        Map<LocalDate, BigDecimal> salesByDay = canSales
                ? aggregateByDay(salesOrderMapper.selectList(
                            new LambdaQueryWrapper<SalesOrder>()
                                    .in(SalesOrder::getStatus,
                                            SalesOrderStatus.APPROVED.name(),
                                            SalesOrderStatus.PARTIAL_OUTBOUND.name(),
                                            SalesOrderStatus.OUTBOUND_DONE.name())
                                    .between(SalesOrder::getApprovedAt,
                                            startDate.atStartOfDay(),
                                            today.atTime(23, 59, 59))),
                        SalesOrder::getApprovedAt,
                        SalesOrder::getTotalAmount)
                : new HashMap<>();
        Map<LocalDate, BigDecimal> purchaseByDay = canPurchase
                ? aggregateByDay(purchaseOrderMapper.selectList(
                            new LambdaQueryWrapper<PurchaseOrder>()
                                    .in(PurchaseOrder::getStatus,
                                            PurchaseOrderStatus.APPROVED.name(),
                                            PurchaseOrderStatus.PARTIAL_INBOUND.name(),
                                            PurchaseOrderStatus.INBOUND_DONE.name())
                                    .between(PurchaseOrder::getApprovedAt,
                                            startDate.atStartOfDay(),
                                            today.atTime(23, 59, 59))),
                        PurchaseOrder::getApprovedAt,
                        PurchaseOrder::getTotalAmount)
                : new HashMap<>();

        List<DashboardTrendPointVO> points = new ArrayList<>(TREND_DAYS);
        // 毛利仅在销售与采购权限都齐备时计算，否则为 0，避免无意义的负毛利曲线
        boolean computeGross = canSales && canPurchase;
        for (int offset = 0; offset < TREND_DAYS; offset++) {
            LocalDate date = startDate.plusDays(offset);
            BigDecimal salesAmount = QtyUtil.toDecimal(salesByDay.getOrDefault(date, BigDecimal.ZERO));
            BigDecimal purchaseAmount = QtyUtil.toDecimal(purchaseByDay.getOrDefault(date, BigDecimal.ZERO));
            // 毛利额是销售额减采购额；亏损日必须保留负值，供前端和对账识别。
            BigDecimal grossMargin = computeGross ? salesAmount.subtract(purchaseAmount) : BigDecimal.ZERO;

            DashboardTrendPointVO vo = new DashboardTrendPointVO();
            vo.setDate(date);
            vo.setSalesAmount(salesAmount);
            vo.setPurchaseAmount(purchaseAmount);
            vo.setGrossMarginAmount(grossMargin);
            points.add(vo);
        }
        return points;
    }

    /** 通用按天聚合：将 entity 按 approved_at 日期聚合 amount 之和 */
    private static <T> Map<LocalDate, BigDecimal> aggregateByDay(List<T> entities,
                                                                Function<T, java.time.LocalDateTime> timeGetter,
                                                                Function<T, Long> amountGetter) {
        Map<LocalDate, BigDecimal> result = new HashMap<>();
        for (T entity : entities) {
            java.time.LocalDateTime when = timeGetter.apply(entity);
            Long amount = amountGetter.apply(entity);
            if (when == null || amount == null) {
                continue;
            }
            LocalDate day = when.toLocalDate();
            result.merge(day, BigDecimal.valueOf(amount), BigDecimal::add);
        }
        return result;
    }
}
