package com.qiheng.erp.warehouse.service.scoring;

import com.qiheng.erp.common.scoring.InboundBillFactsQueryService;
import com.qiheng.erp.common.scoring.InboundFactsView;
import com.qiheng.erp.warehouse.domain.scoring.InboundFactsRow;
import com.qiheng.erp.warehouse.mapper.InboundBillFactsQueryMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 入库单事实查询实现(反转接口的 erp-warehouse 端)。
 *
 * <p>实现 erp-purchase 模块定义的 {@link InboundBillFactsQueryService} 接口,
 * 数据来源是本模块的 {@code inbound_bill} + {@code inbound_bill_item} + {@code stock_bill_item}。
 * SQL 在 {@code InboundBillFactsQueryMapper.xml},Java 端只做 row → view 转换。</p>
 *
 * <p>erppurchase 通过 Spring Bean 注入接口拿到本实现,无需依赖 erp-warehouse。</p>
 *
 * @author Li
 * @since 2026-09-24
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InboundBillFactsQueryServiceImpl implements InboundBillFactsQueryService {

    private final InboundBillFactsQueryMapper factsQueryMapper;

    /**
     * 查 supplier 180 天入库单聚合行,转换为 erp-purchase 视图契约。
     *
     * <p>无数据返回空列表;SQL 返回 null 也视为空。</p>
     */
    @Override
    public List<InboundFactsView> queryRecentFacts(Long supplierId, LocalDateTime since) {
        if (supplierId == null || since == null) {
            return Collections.emptyList();
        }
        List<InboundFactsRow> rows = factsQueryMapper.selectRecentInboundFacts(supplierId, since);
        if (rows == null || rows.isEmpty()) {
            return Collections.emptyList();
        }
        return rows.stream().map(this::toView).collect(Collectors.toList());
    }

    /**
     * row → view 转换。
     *
     * <p>字段映射:物理列 defective_qty → 业务语义 unqualifiedQty;
     * supplierProductId 从 row 透传(NULL 表示手补录/调整入库)。</p>
     */
    private InboundFactsView toView(InboundFactsRow row) {
        return new InboundFactsView(
                row.supplierProductId(),
                row.qualifiedQty(),
                row.defectiveQty(),
                row.totalAmount(),
                row.expectedArrivalDate(),
                row.confirmedAt()
        );
    }
}
