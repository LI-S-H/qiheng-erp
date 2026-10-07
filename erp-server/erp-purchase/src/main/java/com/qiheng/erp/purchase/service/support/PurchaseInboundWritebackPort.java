package com.qiheng.erp.purchase.service.support;

import com.qiheng.erp.purchase.mapper.PurchaseOrderItemMapper;
import com.qiheng.erp.purchase.mapper.SupplierMapper;
import com.qiheng.erp.purchase.mapper.SupplierProductMapper;
import com.qiheng.erp.purchase.service.IPurchaseOrderService;
import com.qiheng.erp.warehouse.domain.common.enums.SourceType;
import com.qiheng.erp.warehouse.domain.inbound.entity.InboundBill;
import com.qiheng.erp.warehouse.domain.inbound.entity.InboundBillItem;
import com.qiheng.erp.warehouse.domain.inbound.port.InboundSourceWritebackPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.math.BigInteger;

/**
 * 采购来源的入库确认回写适配器。
 *
 * <p>职责:</p>
 * <ol>
 *   <li>原有的 handleInboundConfirmation:回写采购订单的 processed_qty / pending_qty / status</li>
 *   <li>本次新增:累加 supplier.score_basis_amount 与 supplier_product.score_basis_amount,
 *       作为供应商价格分的历史金额加权基础；质量分单独使用确认入库明细金额</li>
 * </ol>
 *
 * <p>累加规则:</p>
 * <ul>
 *   <li>仅 PURCHASE_IN + CONFIRMED 触发;</li>
 *   <li>按 source_item_id 关联 purchase_order_item 取 supplier_product_id,按 SP 维度累加;</li>
 *   <li>手补录(source_item_id IS NULL)不计入,避免无来源单价的入库污染金额;</li>
 *   <li>supplier 维度累加按 SP 维度反查 supplier_id 聚合,避免依赖 bill.source_party_id
 *       (历史脏数据 source_party_id=NULL 时仍能正确累加);</li>
 *   <li>累加采用 SQL 表达式,数据库行锁保障并发安全。</li>
 * </ul>
 *
 * <p>所有操作在 {@link com.qiheng.erp.warehouse.service.impl.InboundBillServiceImpl#confirmBill}
 * 的事务内,要么一起提交,要么一起回滚。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PurchaseInboundWritebackPort implements InboundSourceWritebackPort {

    private final IPurchaseOrderService purchaseOrderService;
    private final SupplierMapper supplierMapper;
    private final SupplierProductMapper supplierProductMapper;
    private final PurchaseOrderItemMapper purchaseOrderItemMapper;

    /**
     * 支持的入库来源类型。
     */
    @Override
    public boolean supports(String sourceType) {
        return SourceType.PURCHASE_ORDER.name().equals(sourceType);
    }

    /**
     * 入库单已确认后回写来源单 + 累加 score_basis_amount。
     */
    @Override
    public void onInboundConfirmed(InboundBill bill, List<InboundBillItem> items) {
        // 1. 原有:回写采购订单 processed_qty / pending_qty / status
        purchaseOrderService.handleInboundConfirmation(bill, items);
        // 2. 累加 score_basis_amount，供供应商价格分加权使用。
        if (!"PURCHASE_IN".equals(bill.getInboundType())) {
            return;
        }
        // 2.1 按 SP 维度聚合本次入库金额
        // key = supplierProductId, value = 本次入库金额(分)
        Map<Long, Long> spAmounts = new HashMap<>();
        for (InboundBillItem item : items) {
            if (item.getSourceItemId() == null) {
                // 手补录/调整入库无 source_item_id,不入聚合
                continue;
            }
            Long spId = purchaseOrderItemMapper.selectSupplierProductIdById(item.getSourceItemId());
            if (spId == null) {
                throw new IllegalStateException("采购订单明细缺失供货产品，不能确认入库 sourceItemId=" + item.getSourceItemId());
            }
            // 本次入库金额(分)= current_qty × unit_price / 100
            long currentQty = item.getCurrentQty() == null ? 0L : item.getCurrentQty();
            long unitPrice = item.getUnitPrice() == null ? 0L : item.getUnitPrice();
            // 原始数量和单价均按整数放大存储，先用大整数相乘避免 long 静默溢出。
            long amount = BigInteger.valueOf(currentQty).multiply(BigInteger.valueOf(unitPrice))
                    .divide(BigInteger.valueOf(100L)).longValueExact();
            if (amount <= 0) {
                continue;
            }
            spAmounts.merge(spId, amount, Math::addExact);
        }

        if (spAmounts.isEmpty()) {
            log.debug("入库单无有效累加数据 inboundBillId={}", bill.getId());
            return;
        }
        // 2.2 supplier 维度累加:从 SP 维度反查 supplier_id 聚合,不依赖 bill.source_party_id
        // 这样即使历史脏数据 source_party_id=NULL 也能正确累加
        Map<Long, Long> supplierAmounts = new HashMap<>();
        for (Map.Entry<Long, Long> entry : spAmounts.entrySet()) {
            Long supplierId = supplierProductMapper.selectSupplierIdById(entry.getKey());
            if (supplierId == null) {
                throw new IllegalStateException("供货产品缺失供应商，不能确认入库 spId=" + entry.getKey());
            }
            supplierAmounts.merge(supplierId, entry.getValue(), Math::addExact);
        }
        // 脏数据若跨多个供应商，固定升序取行锁，避免两个入库事务相反顺序等待。
        for (Map.Entry<Long, Long> entry : supplierAmounts.entrySet().stream()
                .sorted(Map.Entry.comparingByKey()).toList()) {
            int rows = supplierMapper.incrementScoreBasisAmount(entry.getKey(), entry.getValue());
            if (rows == 0) {
                throw new IllegalStateException("供应商入库金额累加未生效 supplierId=" + entry.getKey());
            }
        }
        // 2.3 累加每个 SP 的 score_basis_amount
        for (Map.Entry<Long, Long> entry : spAmounts.entrySet().stream()
                .sorted(Map.Entry.comparingByKey()).toList()) {
            int rows = supplierProductMapper.incrementScoreBasisAmount(entry.getKey(), entry.getValue());
            if (rows == 0) {
                throw new IllegalStateException("供货产品入库金额累加未生效 spId=" + entry.getKey());
            }
        }
        log.info("score_basis_amount 累加完成 inboundBillId={} spCount={} supplierCount={} totalAmount={}",
                bill.getId(), spAmounts.size(), supplierAmounts.size(),
                spAmounts.values().stream().reduce(0L, Math::addExact));
    }
}
