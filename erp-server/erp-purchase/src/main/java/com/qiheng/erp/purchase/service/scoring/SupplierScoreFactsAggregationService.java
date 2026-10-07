package com.qiheng.erp.purchase.service.scoring;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qiheng.erp.common.exception.BizException;
import com.qiheng.erp.common.exception.ErrorCode;
import com.qiheng.erp.purchase.domain.purchaseorder.entity.PurchaseOrder;
import com.qiheng.erp.purchase.domain.purchaseorder.entity.PurchaseOrderItem;
import com.qiheng.erp.purchase.domain.purchaseorder.enums.PurchaseOrderStatus;
import com.qiheng.erp.purchase.domain.supplierproduct.entity.SupplierProduct;
import com.qiheng.erp.purchase.mapper.PurchaseOrderItemMapper;
import com.qiheng.erp.purchase.mapper.PurchaseOrderMapper;
import com.qiheng.erp.purchase.mapper.SupplierProductMapper;
import com.qiheng.erp.warehouse.domain.inbound.entity.InboundBill;
import com.qiheng.erp.warehouse.domain.inbound.entity.InboundBillItem;
import com.qiheng.erp.warehouse.mapper.InboundBillItemMapper;
import com.qiheng.erp.warehouse.mapper.InboundBillMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/** 分页读取单据事实后在 Java 归并；完整订单金额与批次事实不会因联表被重复计入。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SupplierScoreFactsAggregationService {
    private static final int PAGE_SIZE = 200;
    private final PurchaseOrderMapper orderMapper;
    private final PurchaseOrderItemMapper orderItemMapper;
    private final InboundBillMapper inboundMapper;
    private final InboundBillItemMapper inboundItemMapper;
    private final SupplierProductMapper supplierProductMapper;

    /** 合格与不合格原始金额，统一使用数量存储值乘以单价分，避免逐批舍入。 */
    public record QualityAmount(BigInteger qualifiedRaw, BigInteger defectiveRaw) {
        /**
         * 创建没有有效金额的初始累计值。
         *
         * @return 合格和不合格金额均为零的累计值
         */
        public static QualityAmount zero() {
            return new QualityAmount(BigInteger.ZERO, BigInteger.ZERO);
        }
        /**
         * 合并另一份质检金额，保持原始整数精度。
         *
         * @param other 待合并的质检金额
         * @return 合并后的新金额对象，不修改原对象
         */
        public QualityAmount add(QualityAmount other) {
            return new QualityAmount(qualifiedRaw.add(other.qualifiedRaw), defectiveRaw.add(other.defectiveRaw));
        }
    }

    /** 窗口质量事实；产品金额只供本次计算，今日订单集合供缓存去重。 */
    public record QualityFacts(Long supplierId, Map<Long, QualityAmount> productAmounts,
                               BigInteger qualifiedAmountRaw, BigInteger defectiveAmountRaw,
                               long validOrders, long skippedOrders, Set<Long> includedTodayOrderIds) {
        // 防止外部修改内部集合，导致并发异常
        public QualityFacts {
            productAmounts = Map.copyOf(productAmounts);
            includedTodayOrderIds = Set.copyOf(includedTodayOrderIds);
        }
        // 无今日订单集合的构造函数，用于初始化
        public QualityFacts(Long supplierId, Map<Long, QualityAmount> productAmounts,
                            BigInteger qualifiedAmountRaw, BigInteger defectiveAmountRaw,
                            long validOrders, long skippedOrders) {
            this(supplierId, productAmounts, qualifiedAmountRaw, defectiveAmountRaw, validOrders, skippedOrders, Set.of());
        }
    }

    /** 本批完成订单的独立质量贡献及其涉及产品，异常订单也保留产品范围供后续校正。 */
    public record CompletedOrderFacts(Map<Long, QualityFacts> orders, Set<Long> supplierProductIds) {
        public CompletedOrderFacts {
            orders = Map.copyOf(orders);
            supplierProductIds = Set.copyOf(supplierProductIds);
        }
    }

    /** 窗口交付事实；应交金额单位为分，罚额保留亚分精度用于评分。 */
    public record DeliveryFacts(Long supplierId, long dueAmountCents, BigDecimal penaltyAmountRaw,
                                long validOrders, long skippedOrders) {
        public DeliveryFacts(Long supplierId, long dueAmountCents, long penaltyAmountCents,
                             long validOrders, long skippedOrders) {
            this(supplierId, dueAmountCents, BigDecimal.valueOf(penaltyAmountCents), validOrders, skippedOrders);
        }
        /**
         * 将罚额舍入为整数分，仅供对账展示，评分必须读取原始罚额。
         *
         * @return 四舍五入后的罚额分值
         */
        public long penaltyAmountCents() {
            return penaltyAmountRaw.setScale(0, RoundingMode.HALF_UP).longValueExact();
        }
    }

    /**
     * 汇总近 180 个自然日内完全入库订单的产品及供应商质量金额。
     * 订单按完成时间选取，关联确认入库批次不再次按时间截断。
     *
     * @param supplierId 供应商 ID
     * @param businessDate 上海时区的评分业务日期，质量窗口包含当天
     * @return 产品质量金额、供应商总额、有效及异常订单数量和今日订单集合
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public QualityFacts queryQualityFacts(Long supplierId, LocalDate businessDate) {
        return queryQualityFacts(supplierId, businessDate, null);
    }

    /**
     * 汇总指定产品的完整质量窗口；候选订单必须整单校验，金额只纳入目标产品。
     *
     * @param supplierId 供应商 ID
     * @param businessDate 上海时区业务日期，质量窗口包含当天
     * @param supplierProductIds 目标供货产品集合；null 表示全部，空集合不查询
     * @return 查询范围内的质量金额与订单校验结果；没有有效样本的产品不生成零额记录
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public QualityFacts queryQualityFacts(Long supplierId, LocalDate businessDate, Set<Long> supplierProductIds) {
        Map<Long, QualityAmount> amounts = new HashMap<>();
        Set<Long> includedTodayOrders = new HashSet<>();
        if (supplierProductIds != null && supplierProductIds.isEmpty()) {
            return new QualityFacts(supplierId, amounts, BigInteger.ZERO, BigInteger.ZERO, 0, 0);
        }
        // 产品标识分批限制 IN 长度，同一订单跨产品批命中时只校验和累计一次。
        List<Set<Long>> productPages = new ArrayList<>();
        if (supplierProductIds == null) {
            productPages.add(null);
        } else {
            List<Long> productIds = new ArrayList<>(supplierProductIds);
            for (int start = 0; start < productIds.size(); start += PAGE_SIZE) {
                productPages.add(Set.copyOf(productIds.subList(start, Math.min(start + PAGE_SIZE, productIds.size()))));
            }
        }
        // 已处理订单，避免重复校验。
        Set<Long> processedOrders = new HashSet<>();
        // 缓存中不存在的日期订单，用于校正。
        Set<Long> missingDateOrders = new HashSet<>();
        long valid = 0, skipped = 0;
        for (Set<Long> productPage : productPages) {
            long cursor = 0;
            while (true) {
                // 1. 分页定位窗口订单；产品过滤只定位整单，不截断入库批次。
                List<PurchaseOrder> orders = productPage == null ? orderMapper.selectList(new LambdaQueryWrapper<PurchaseOrder>()
                            .select(PurchaseOrder::getId, PurchaseOrder::getSupplierId, PurchaseOrder::getStatus,
                                PurchaseOrder::getFullyReceivedAt, PurchaseOrder::getPurchaseNo)
                            .eq(PurchaseOrder::getSupplierId, supplierId)
                            .eq(PurchaseOrder::getStatus, PurchaseOrderStatus.INBOUND_DONE)
                            .ge(PurchaseOrder::getFullyReceivedAt, businessDate.minusDays(179).atStartOfDay())
                            .lt(PurchaseOrder::getFullyReceivedAt, businessDate.plusDays(1).atStartOfDay())
                            .gt(PurchaseOrder::getId, cursor).orderByAsc(PurchaseOrder::getId)
                            .last("LIMIT " + PAGE_SIZE))
                        : orderMapper.selectQualityOrdersForProducts(supplierId, businessDate.minusDays(179).atStartOfDay(),
                        businessDate.plusDays(1).atStartOfDay(), productPage, cursor, PAGE_SIZE, false);
                if (orders.isEmpty()) break;
                // 2. 重用整单来源和数量校验，跨产品分批命中的订单不重复计入。
                List<PurchaseOrder> uniqueOrders = orders.stream().filter(order -> processedOrders.add(order.getId())).toList();
                PageFacts page = uniqueOrders.isEmpty() ? null : loadPage(uniqueOrders, true);
                for (PurchaseOrder order : uniqueOrders) {
                    try {
                        Map<Long, QualityAmount> orderAmounts = qualityForOrder(order, page);
                        orderAmounts.forEach((id, amount) -> {
                            if (supplierProductIds == null || supplierProductIds.contains(id)) {
                                amounts.merge(id, amount, QualityAmount::add);
                            }
                        });
                        if (order.getFullyReceivedAt().toLocalDate().equals(businessDate)) {
                            includedTodayOrders.add(order.getId());
                        }
                        valid++;
                    } catch (InvalidOrder e) {
                        skipped++;
                        warn(order, "质量", e);
                    }
                }
                cursor = orders.getLast().getId();
                if (orders.size() < PAGE_SIZE) break;
            }
            // 缺失完成时间不能猜测窗口归属，仅告警，不以 update_time 代替。
            cursor = 0;
            while (true) {
                List<PurchaseOrder> missingDates = productPage == null ? orderMapper.selectList(new LambdaQueryWrapper<PurchaseOrder>()
                            .select(PurchaseOrder::getId, PurchaseOrder::getPurchaseNo)
                            .eq(PurchaseOrder::getSupplierId, supplierId)
                            .eq(PurchaseOrder::getStatus, PurchaseOrderStatus.INBOUND_DONE)
                            .isNull(PurchaseOrder::getFullyReceivedAt)
                            .gt(PurchaseOrder::getId, cursor)
                            .orderByAsc(PurchaseOrder::getId).last("LIMIT " + PAGE_SIZE))
                        : orderMapper.selectQualityOrdersForProducts(supplierId, businessDate.minusDays(179).atStartOfDay(),
                        businessDate.plusDays(1).atStartOfDay(), productPage, cursor, PAGE_SIZE, true);
                for (PurchaseOrder order : missingDates) {
                    if (missingDateOrders.add(order.getId())) {
                        warn(order, "质量", new InvalidOrder("完全入库订单缺少完成时间"));
                        skipped++;
                    }
                }
                if (missingDates.size() < PAGE_SIZE) {
                    break;
                }
                cursor = missingDates.getLast().getId();
            }
        }
        QualityAmount total = amounts.values().stream().reduce(QualityAmount.zero(), QualityAmount::add);
        return new QualityFacts(supplierId, amounts, total.qualifiedRaw(), total.defectiveRaw(), valid, skipped, includedTodayOrders);
    }

    /**
     * 查询当日完全入库订单的全部批次，提取提交后白天增量所需的金额。
     *
     * @param orderId 当日首次完全入库的采购单 ID
     * @param businessDate 上海时区的评分业务日期
     * @return 单张订单的产品及供应商质量金额；异常订单返回零额并计入跳过数量
     * @throws IllegalArgumentException 订单不是当日完成的完全入库订单
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public QualityFacts queryQualityContribution(Long orderId, LocalDate businessDate) {
        PurchaseOrder order = orderMapper.selectById(orderId);
        if (order == null || !PurchaseOrderStatus.INBOUND_DONE.name().equals(order.getStatus()) || order.getFullyReceivedAt() == null
                || !order.getFullyReceivedAt().toLocalDate().equals(businessDate)) {
            throw new IllegalArgumentException("白天质量增量仅接受当天首次完全入库订单，历史订单由全量校正处理");
        }
        try {
            // 1. 计算对应供货产品的总质检金额(合格金额+不合格金额)
            Map<Long, QualityAmount> amounts = qualityForOrder(order, loadPage(List.of(order), true));
            // 2. 记录今日订单总质检金额(合格金额,不合格金额)
            QualityAmount total = amounts.values().stream().reduce(QualityAmount.zero(), QualityAmount::add);
            return new QualityFacts(order.getSupplierId(), amounts, total.qualifiedRaw(), total.defectiveRaw(), 1, 0);
        } catch (InvalidOrder e) {
            warn(order, "质量增量", e);
            return new QualityFacts(order.getSupplierId(), Map.of(), BigInteger.ZERO, BigInteger.ZERO, 0, 1);
        }
    }

    /**
     * 批量提取当日完全入库订单的质量贡献，整单校验且包含提前确认的全部入库批次。
     *
     * @param supplierId 本次重算供应商 ID，必须与全部来源订单归属一致
     * @param orderIds 本批完成采购单集合，重复标识只处理一次
     * @param businessDate 上海时区业务日期
     * @return 每张订单的独立金额和受影响产品；异常样本告警并返回零贡献
     * @throws IllegalArgumentException 来源订单不属于当日完全入库增量
     * @throws BizException 来源订单不存在或跨供应商，禁止静默退回全量处理
     */
    // 跨日校验异常仅通知上层切换完整窗口；只读查询不能因此标记外层重算事务回滚。
    // 不存在或跨供应商的 BizException 仍按默认规则回滚，禁止吞掉真实来源错误。
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, noRollbackFor = IllegalArgumentException.class)
    public CompletedOrderFacts queryQualityContributions(Long supplierId, Collection<Long> orderIds, LocalDate businessDate) {
        Objects.requireNonNull(orderIds, "完成订单集合不能为空");
        List<Long> ids = new ArrayList<>(new LinkedHashSet<>(orderIds));
        if (ids.contains(null)) {
            throw new IllegalArgumentException("完成订单标识不能为空");
        }
        // 1. 从数据库查询质量贡献
        Map<Long, QualityFacts> contributions = new HashMap<>();
        Set<Long> affectedProducts = new HashSet<>();
        for (int start = 0; start < ids.size(); start += PAGE_SIZE) {
            // 2. 分页查询订单，避免内存溢出
            List<Long> requested = ids.subList(start, Math.min(start + PAGE_SIZE, ids.size()));
            List<PurchaseOrder> orders = orderMapper.selectByIds(requested);
            if (orders.size() != requested.size()) {
                throw new BizException(ErrorCode.DATA_NOT_FOUND.getCode(), "评分来源采购单不存在或已删除");
            }
            // 3. 校验订单状态是否符合要求
            for (PurchaseOrder order : orders) {
                if (!Objects.equals(order.getSupplierId(), supplierId)) {
                    throw new BizException(ErrorCode.PARAM_ERROR.getCode(), "评分来源采购单供应商不一致");
                }
                if (!PurchaseOrderStatus.INBOUND_DONE.name().equals(order.getStatus()) || order.getFullyReceivedAt() == null
                        || !order.getFullyReceivedAt().toLocalDate().equals(businessDate)) {
                    throw new IllegalArgumentException("白天质量增量仅接受当天首次完全入库订单，历史订单由全量校正处理");
                }
            }
            // 4. 加载订单详情(
            //   采购单明细(采购单ID->明细列表),
            //   入库单明细(采购单ID->入库单明细列表),
            //   入库单(入库单ID->入库单),
            //   供货产品(供货产品ID->供货产品))
            PageFacts page = loadPage(orders, true);
            for (PurchaseOrder order : orders) {
                // 即便异常整单跳过，也保留相关产品用于窗口查询，避免沿用过时的质量分。
                page.items().getOrDefault(order.getId(), List.of()).stream().map(PurchaseOrderItem::getSupplierProductId)
                        .filter(Objects::nonNull).forEach(affectedProducts::add);
                try {
                    // 5. 计算对应供货产品的总质检金额(合格金额+不合格金额)
                    Map<Long, QualityAmount> amounts = qualityForOrder(order, page);
                    // 6. 记录今日订单总质检金额(合格金额,不合格金额)
                    QualityAmount total = amounts.values().stream().reduce(QualityAmount.zero(), QualityAmount::add);
                    contributions.put(order.getId(), new QualityFacts(supplierId, amounts, total.qualifiedRaw(),
                            total.defectiveRaw(), 1, 0, Set.of(order.getId())));
                } catch (InvalidOrder e) {
                    warn(order, "质量增量", e);
                    contributions.put(order.getId(), new QualityFacts(supplierId, Map.of(), BigInteger.ZERO,
                            BigInteger.ZERO, 0, 1, Set.of(order.getId())));
                }
            }
        }
        return new CompletedOrderFacts(contributions, affectedProducts);
    }

    /**
     * 汇总过去 180 天内已到期订单的应交金额与逾期折算罚额。
     * 已交批次按确认日期计罚，未交部分按业务日期计罚，预计到货当天尚不纳入。
     *
     * @param supplierId 供应商 ID
     * @param businessDate 上海时区的评分业务日期，交付窗口不包含当天
     * @return 应交金额、未舍入罚额及有效和异常订单数量
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public DeliveryFacts queryDeliveryFacts(Long supplierId, LocalDate businessDate) {
        long due = 0, valid = 0, skipped = 0, cursor = 0;
        BigDecimal penalty = BigDecimal.ZERO;
        while (true) {
            // 1. 查询过去 180 天内已到期采购订单
            List<PurchaseOrder> orders = orderMapper.selectList(new LambdaQueryWrapper<PurchaseOrder>()
                    .select(PurchaseOrder::getId, PurchaseOrder::getSupplierId, PurchaseOrder::getStatus,
                            PurchaseOrder::getExpectedArrivalDate, PurchaseOrder::getPurchaseNo)
                    .eq(PurchaseOrder::getSupplierId, supplierId)
                    .ge(PurchaseOrder::getExpectedArrivalDate, businessDate.minusDays(180))
                    .lt(PurchaseOrder::getExpectedArrivalDate, businessDate)
                    .in(PurchaseOrder::getStatus, PurchaseOrderStatus.APPROVED, PurchaseOrderStatus.PARTIAL_INBOUND, PurchaseOrderStatus.INBOUND_DONE)
                    .gt(PurchaseOrder::getId, cursor).orderByAsc(PurchaseOrder::getId)
                    .last("LIMIT " + PAGE_SIZE));
            if (orders.isEmpty()) break;
            PageFacts page = loadPage(orders, false);
            // 2. 按采购单汇总应交金额和逾期折算罚额，异常订单整单跳过。
            for (PurchaseOrder order : orders) {
                try {
                    DeliveryAmount amount = deliveryForOrder(order, page, businessDate);
                    due = Math.addExact(due, amount.due());
                    penalty = penalty.add(amount.penalty());
                    valid++;
                } catch (InvalidOrder e) {
                    skipped++;
                    warn(order, "交付", e);
                }
            }
            cursor = orders.getLast().getId();
            if (orders.size() < PAGE_SIZE) break;
        }
        return new DeliveryFacts(supplierId, due, penalty, valid, skipped);
    }

    /** 分批读取明细、确认入库批次及可选产品归属，按采购单在内存中建立关联。 */
    private PageFacts loadPage(List<PurchaseOrder> orders, boolean checkProductOwners) {
        Set<Long> ids = orders.stream().map(PurchaseOrder::getId).collect(Collectors.toSet());
        // 1. 采购明细包含来源标识、供货产品、采购数量、累计入库数量和总金额。
        List<PurchaseOrderItem> items = orderItemMapper.selectList(new LambdaQueryWrapper<PurchaseOrderItem>()
                .select(PurchaseOrderItem::getId, PurchaseOrderItem::getPurchaseOrderId,
                        PurchaseOrderItem::getSupplierProductId, PurchaseOrderItem::getQuantity,
                        PurchaseOrderItem::getInboundQty, PurchaseOrderItem::getTotalAmount)
                .in(PurchaseOrderItem::getPurchaseOrderId, ids));
        // 2. 入库单(包括id,采购单id,确认日期)
        List<InboundBill> bills = inboundMapper.selectList(new LambdaQueryWrapper<InboundBill>()
                .select(InboundBill::getId, InboundBill::getSourceId, InboundBill::getConfirmedAt)
                .eq(InboundBill::getSourceType, "PURCHASE_ORDER").eq(InboundBill::getInboundType, "PURCHASE_IN")
                .eq(InboundBill::getStatus, "CONFIRMED").in(InboundBill::getSourceId, ids));
        // 3. 入库单映射 (id -> bill)
        Map<Long, InboundBill> billById = bills.stream().collect(Collectors.toMap(InboundBill::getId, b -> b));
        // 4. 入库明细包含来源明细标识、实际数量、合格/不合格数量和入库单价快照。
        List<InboundBillItem> inboundItems = new ArrayList<>();
        List<Long> billIds = new ArrayList<>(billById.keySet());
        for (int start = 0; start < billIds.size(); start += PAGE_SIZE) {
            inboundItems.addAll(inboundItemMapper.selectList(new LambdaQueryWrapper<InboundBillItem>()
                .select(InboundBillItem::getId, InboundBillItem::getInboundBillId, InboundBillItem::getSourceItemId,
                        InboundBillItem::getCurrentQty, InboundBillItem::getQualifiedQty,
                        InboundBillItem::getDefectiveQty, InboundBillItem::getUnitPrice)
                .in(InboundBillItem::getInboundBillId, billIds.subList(start, Math.min(start + PAGE_SIZE, billIds.size())))));
        }
        Set<Long> spIds = items.stream().map(PurchaseOrderItem::getSupplierProductId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        // 6. 供货产品归属映射 (id -> supplierProduct)
        Map<Long, SupplierProduct> products = new HashMap<>();
        if (checkProductOwners) {
            List<Long> productIds = new ArrayList<>(spIds);
            for (int start = 0; start < productIds.size(); start += PAGE_SIZE) {
                supplierProductMapper.selectScoringOwners(productIds.subList(start, Math.min(start + PAGE_SIZE, productIds.size())))
                        .forEach(product -> products.put(product.getId(), product));
            }
        }
        return new PageFacts(items.stream().collect(Collectors.groupingBy(PurchaseOrderItem::getPurchaseOrderId)),
                inboundItems.stream().collect(Collectors.groupingBy(i -> billById.get(i.getInboundBillId()).getSourceId())),
                billById, products);
    }

    /** 校验整单后，使用入库明细单价与质检数量汇总各供货产品质量金额。 */
    private Map<Long, QualityAmount> qualityForOrder(PurchaseOrder order, PageFacts page) {
        Map<Long, QualityAmount> result = new HashMap<>();
        // 1. 校验采购单明细和入库单明细数量一致,并获取入库单明细映射(采购单明细id -> 入库单明细)
        Map<Long, List<InboundBillItem>> batches = validate(order, page, true, true);
        for (PurchaseOrderItem item : page.items().get(order.getId())) {
            QualityAmount amount = QualityAmount.zero();
            // 2. 汇总质检金额(合格金额,不合格金额)
            for (InboundBillItem batch : batches.getOrDefault(item.getId(), List.of())) {
                if (batch.getUnitPrice() == null || batch.getUnitPrice() <= 0){
                    throw invalid("入库单价快照无效");
                }
                // 3. 计算质检金额
                BigInteger price = BigInteger.valueOf(batch.getUnitPrice());
                amount = amount.add(new QualityAmount(BigInteger.valueOf(batch.getQualifiedQty()).multiply(price),
                        BigInteger.valueOf(batch.getDefectiveQty()).multiply(price)));
            }
            result.merge(item.getSupplierProductId(), amount, QualityAmount::add);
        }
        return result;
    }

    /** 汇总整单应交金额，按批次实际确认日期和未交余额的业务日期计算罚额。 */
    private DeliveryAmount deliveryForOrder(PurchaseOrder order, PageFacts page, LocalDate date) {
        // 1. 校验采购单明细和入库单明细数量一致,并获取入库单明细映射(采购单明细id -> 入库单明细)
        Map<Long, List<InboundBillItem>> batches = validate(order, page, PurchaseOrderStatus.INBOUND_DONE.name().equals(order.getStatus()), false);
        long due = 0;
        BigDecimal penalty = BigDecimal.ZERO;
        // 2. 汇总应交金额(应交金额,逾期金额)
        for (PurchaseOrderItem item : page.items().get(order.getId())) {
            if (item.getTotalAmount() == null || item.getTotalAmount() < 0) {
                throw invalid("采购明细金额无效");
            }
            // 3. 计算应交金额
            long lineAmount = item.getTotalAmount();
            due = Math.addExact(due, lineAmount);
            // 已确认数量
            long receivedQty = 0;
            // 逾期系数数量(100倍)
            BigInteger weightedQty = BigInteger.ZERO;
            // 4. 计算逾期金额
            for (InboundBillItem batch : batches.getOrDefault(item.getId(), List.of())) {
                receivedQty = Math.addExact(receivedQty, batch.getCurrentQty());
                int rate = penaltyRate(order.getExpectedArrivalDate(),
                        page.bills().get(batch.getInboundBillId()).getConfirmedAt().toLocalDate());
                // 4.1 该批次逾期数量 * 逾期百分比(100倍)
                weightedQty = weightedQty.add(BigInteger.valueOf(batch.getCurrentQty()).multiply(BigInteger.valueOf(rate)));
            }
            // 4.2 算未到货数量逾期系数数量,就是未到货数量 * 逾期百分比(100倍)
            weightedQty = weightedQty.add(BigInteger.valueOf(item.getQuantity() - receivedQty)
                    .multiply(BigInteger.valueOf(penaltyRate(order.getExpectedArrivalDate(), date))));
            // 4.3 计算逾期金额(用整行金额 * (逾期系数数量 / 总数量 * 100) 来计算)
            // 除法保留32位亚分精度，业务分数才舍入为 INT×100，批次与剩余数量始终配平。
            penalty = penalty.add(BigDecimal.valueOf(lineAmount).multiply(new BigDecimal(weightedQty))
                    .divide(BigDecimal.valueOf(item.getQuantity()).multiply(BigDecimal.valueOf(100)), 32, RoundingMode.HALF_UP));
        }
        return new DeliveryAmount(due, penalty);
    }

    /** 核对整单来源、质检数量、累计入库及可选产品归属，异常时拒绝整个样本。 */
    private Map<Long, List<InboundBillItem>> validate(PurchaseOrder order, PageFacts page, boolean completed, boolean checkProductOwners) {
        // 1. 校验采购单明细
        List<PurchaseOrderItem> items = page.items().getOrDefault(order.getId(), List.of());
        if (items.isEmpty()) throw invalid("采购单缺少明细");
        // 获取关联采购单明细(id -> purchaseOrderItem)
        Map<Long, PurchaseOrderItem> byId = items.stream().collect(Collectors.toMap(PurchaseOrderItem::getId, i -> i));
        Map<Long, List<InboundBillItem>> batches = new HashMap<>();
        // 2. 校验入库单明细
        for (InboundBillItem batch : page.inboundItems().getOrDefault(order.getId(), List.of())) {
            // 2.1 获取关联入库单
            InboundBill bill = page.bills().get(batch.getInboundBillId());
            // 2.2 校验入库单确认日期与来源明细是否存在
            if (bill.getConfirmedAt() == null || !byId.containsKey(batch.getSourceItemId())) {
                throw invalid("确认时间或来源明细缺失");
            }
            // 2.3 校验入库单明细数量
            if (batch.getCurrentQty() == null || batch.getCurrentQty() <= 0 || batch.getQualifiedQty() == null
                    || batch.getDefectiveQty() == null || batch.getQualifiedQty() < 0 || batch.getDefectiveQty() < 0
                    || Math.addExact(batch.getQualifiedQty(), batch.getDefectiveQty()) != batch.getCurrentQty()) {
                throw invalid("入库质检数量不一致");
            }
            // 2.4 添加入库单明细到采购单明细映射(id -> inboundBillItem)
            batches.computeIfAbsent(batch.getSourceItemId(), ignored -> new ArrayList<>()).add(batch);
        }
        // 3. 校验采购单明细数量
        for (PurchaseOrderItem item : items) {
            // 3.1 校验产品归属
            if (checkProductOwners) {
                SupplierProduct product = page.products().get(item.getSupplierProductId());
                if (product == null || !Objects.equals(product.getSupplierId(), order.getSupplierId())) throw invalid("供货产品归属无效");
            }
            // 3.2 校验采购单明细数量
            if (item.getQuantity() == null || item.getQuantity() <= 0 || item.getInboundQty() == null
                    || item.getInboundQty() < 0 || item.getInboundQty() > item.getQuantity()) throw invalid("采购明细数量无效");
            // 3.3 校验采购单明细数量与入库单明细数量一致
            long received = batches.getOrDefault(item.getId(), List.of()).stream().mapToLong(InboundBillItem::getCurrentQty).reduce(0, Math::addExact);
            if (received != item.getInboundQty() || (completed && received != item.getQuantity())) {
                throw invalid("确认批次数量与采购累计入库不一致");
            }
        }
        return batches;
    }

    /** 根据实际到货或当前业务日期与预计到货日的差值选择逾期百分比。 */
    private static int penaltyRate(LocalDate expected, LocalDate received) {
        long days = ChronoUnit.DAYS.between(expected, received);
        return days <= 0 ? 0 : days <= 3 ? 25 : days <= 7 ? 50 : days <= 15 ? 75 : 100;
    }

    /** 将数据校验失败转换为整单跳过的内部异常。 */
    private static InvalidOrder invalid(String reason) {
        return new InvalidOrder(reason);
    }

    /** 输出可定位到采购单的异常样本告警，不修改历史业务数据。 */
    private static void warn(PurchaseOrder order, String metric, InvalidOrder error) {
        log.warn("跳过异常采购评分样本 metric={} orderId={} purchaseNo={} reason={}", metric, order.getId(), order.getPurchaseNo(), error.getMessage());
    }

    /**
     * 采购单数据页。
     *
     * @param items 采购单明细(采购单ID->明细列表)
     * @param inboundItems 入库单明细(采购单ID->入库单明细列表)
     * @param bills 入库单(入库单ID->入库单)
     * @param products 供货产品(供货产品ID->供货产品)
     */
    private record PageFacts(Map<Long, List<PurchaseOrderItem>> items, Map<Long, List<InboundBillItem>> inboundItems,
                             Map<Long, InboundBill> bills, Map<Long, SupplierProduct> products) { }

    /**
     * 采购单交付金额。
     *
     * @param due 应交金额（分）
     * @param penalty 逾期折算罚额（分，保留亚分精度）
     */
    private record DeliveryAmount(long due, BigDecimal penalty) { }

    /** 采购单异常样本，捕获后告警并跳过整张订单。 */
    private static final class InvalidOrder extends RuntimeException {
        private InvalidOrder(String message) { super(message); }
    }
}
