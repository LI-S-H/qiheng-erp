package com.qiheng.erp.purchase.service.impl;

import com.qiheng.erp.common.constant.SupplierScoreRedisKeys;
import com.qiheng.erp.common.util.BillNoGenerator;
import com.qiheng.erp.common.exception.BizException;
import com.qiheng.erp.common.exception.ErrorCode;
import com.qiheng.erp.purchase.domain.supplier.entity.Supplier;
import com.qiheng.erp.purchase.domain.supplierproduct.entity.SupplierProduct;
import com.qiheng.erp.purchase.domain.supplierscore.constant.SupplierScoreConstants;
import com.qiheng.erp.purchase.domain.supplierscore.dto.FactsSnapshot;
import com.qiheng.erp.purchase.domain.supplierscore.dto.RecalcContext;
import com.qiheng.erp.purchase.domain.supplierscore.dto.ScoreChangeBatchCommand;
import com.qiheng.erp.purchase.domain.supplierscore.dto.ScoreChangeLogEntry;
import com.qiheng.erp.purchase.domain.supplierscore.dto.ScoreChangeSource;
import com.qiheng.erp.purchase.domain.supplierscore.enums.ScoreSourceBusinessType;
import com.qiheng.erp.security.context.UserContext;
import com.qiheng.erp.purchase.domain.supplierscore.dto.ScoreRecalcContext;
import com.qiheng.erp.purchase.domain.supplierscore.dto.ScoreRecalcResult;
import com.qiheng.erp.purchase.domain.supplierscore.enums.MetricType;
import com.qiheng.erp.purchase.domain.supplierscore.enums.OperatorType;
import com.qiheng.erp.purchase.domain.supplierscore.enums.TriggerType;
import com.qiheng.erp.purchase.domain.supplierproduct.enums.SupplierScoreStatus;
import com.qiheng.erp.purchase.service.ISupplierScoreChangeLogService;
import com.qiheng.erp.purchase.service.SupplierScoreRecalculateService;
import com.qiheng.erp.purchase.service.scoring.AggregateScoreCalculator;
import com.qiheng.erp.purchase.service.scoring.DeliveryScoreCalculator;
import com.qiheng.erp.purchase.service.scoring.QualityScoreCalculator;
import com.qiheng.erp.purchase.service.scoring.PriceScoreCalculator;
import com.qiheng.erp.purchase.service.scoring.ScoreFactsQueryService;
import com.qiheng.erp.purchase.service.scoring.SupplierScoreFactsAggregationService;
import com.qiheng.erp.purchase.mapper.SupplierMapper;
import com.qiheng.erp.purchase.mapper.SupplierProductMapper;
import com.qiheng.erp.purchase.mapper.SupplierScoreChangeLogMapper;
import com.qiheng.erp.purchase.domain.supplierscore.dto.SupplierPricePair;
import com.qiheng.erp.product.mapper.ProductMapper;
import com.qiheng.erp.product.domain.entity.Product;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import java.math.RoundingMode;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.HashSet;
import java.util.Set;

/**
 * 评分重算实现。
 *
 * <p>同步入口(A1/A2/A3)由业务 Service 事务内调用,事务边界由调用方控制,
 * 同步入口要求已有事务，不单独开启事务。异步入口由 Consumer / 定时任务调用，
 * 本 Service 开启事务，并在首次普通数据库读取前取得供应商行锁。</p>
 *
 * <p>外层消费者和每日任务持有供应商 Redis 锁；同步业务方先取得供应商数据库行锁，
 * 参考价入口按供应商 ID 升序取得行锁。禁止持行锁后首次争抢供应商 Redis 锁。
 * 服务分入口仅重算综合分和产品推荐分；价格入口仅重算相关产品价格及供应商价格汇总，
 * 两者均不查询 180 天质量和交付事实。</p>
 * <p>事实入口使用同一数据库快照重算质量、价格及衍生分，全量校正同时重算交付分。
 * 多订单合并按采购单幂等追加质量总额，只有冷启动和每日校正完整重建事实缓存。
 * 持久化只更新变化字段并校验版本。
 * 各入口共用变化日志构造，评分状态修正没有指标变化时不额外写日志。</p>
 *
 * <p>正常完全入库只查询相关产品的质量窗口并沿用已有交付分；旧交付分缺失时从数据库补算。
 * 逾期审批、取消与部分入库引起的交付变化允许延迟到每日全量校正。</p>
 *
 * @author Li
 * @since 2026-09-23
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SupplierScoreRecalculateServiceImpl implements SupplierScoreRecalculateService {

    private final QualityScoreCalculator qualityCalculator;
    private final DeliveryScoreCalculator deliveryCalculator;
    private final PriceScoreCalculator priceCalculator;
    private final AggregateScoreCalculator aggregateCalculator;
    private final ScoreFactsQueryService factsQueryService;
    private final SupplierScoreFactsAggregationService factsAggregationService;
    private final ISupplierScoreChangeLogService changeLogService;
    private final SupplierMapper supplierMapper;
    private final SupplierProductMapper supplierProductMapper;
    private final ProductMapper productMapper;
    private final BillNoGenerator billNoGenerator;
    private final SupplierScoreChangeLogMapper changeLogMapper;

    // ============================================
    // 同步入口(A1/A2/A3)
    // ============================================

    /**
     * 服务分人工调整后重算。
     * <p>重算：供应商综合分、全部供货产品推荐分。</p>
     * <p>不重算：供应商质量分/交付分/价格分、供货产品质量分/价格分（不查180天事实）。</p>
     * <p>服务分由调用方先写库，此处只读新值参与聚合。</p>
     *
     * @param supplierId 目标供应商 ID
     * @param beforeServiceScore 调整前的服务分，可为空，供变化日志使用
     * @param reason 人工调整原因
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public void recalcForServiceScoreChange(Long supplierId, Integer beforeServiceScore, String reason) {
        RecalcContext ctx = RecalcContext.builder()
                .triggerType(TriggerType.SERVICE_TRIGGER)
                .operatorType(OperatorType.USER.name())
                .reason(reason)
                .build();
        // 1. 读取供应商及产品信息
        Supplier supplier = supplierMapper.selectById(supplierId);
        if (supplier == null) return;
        ctx.setRelatedSources(List.of(new ScoreChangeSource(ScoreSourceBusinessType.SUPPLIER,
                supplierId.toString(), supplier.getSupplierCode())));
        List<SupplierProduct> products = supplierProductMapper.selectList(
                new LambdaQueryWrapper<SupplierProduct>().eq(SupplierProduct::getSupplierId, supplierId));
        // 2. 填充计算辅助信息
        CalculatedScores scores = new CalculatedScores();
        scores.deliveryScore = supplier.getDeliveryScore();
        scores.supplierQualityScore = supplier.getQualityScore();
        scores.supplierPriceScore = supplier.getPriceScore();
        scores.overallScore = aggregateCalculator.calculateOverallScore(scores.supplierPriceScore,
                scores.deliveryScore, scores.supplierQualityScore, supplier.getServiceScore());
        // 3. 记录指标变化
        ScoreRecalcResult result = ScoreRecalcResult.builder().supplierId(supplierId)
                .metricChanges(new ArrayList<>()).build();
        // 3.1 记录服务分变化,不需要记录产品推荐分变化
        addIfChanged(result.getMetricChanges(), MetricType.SERVICE, null, beforeServiceScore,
                supplier.getServiceScore(), null, null, supplier.getOverallScore(), scores.overallScore);
        for (SupplierProduct p : products) {
            // 3.2 重算产品推荐分
            scores.productPriceScore.put(p.getId(), p.getPriceScore());
            scores.productQualityScore.put(p.getId(), p.getQualityScore());
            Integer recommend = aggregateCalculator.calculateRecommendScore(p.getPriceScore(),
                    supplier.getDeliveryScore(), p.getQualityScore(), supplier.getServiceScore());
            scores.productRecommendScore.put(p.getId(), recommend);
            // 3.3 记录产品推荐分变化,不需记录供应商综合分变化,因为变化的是供应商级别的服务分
            addIfChanged(result.getMetricChanges(), MetricType.SERVICE, p.getId(), null, null,
                    p.getRecommendScore(), recommend, null, null);
        }
        // 3.4 记录是否变化,有指标变化或评分状态变化才写日志
        result.setChanged(!result.getMetricChanges().isEmpty()
                || !Objects.equals(supplier.getScoreStatus(), scores.overallScore == null ? SupplierScoreStatus.NOT_READY.name() : SupplierScoreStatus.READY.name())
                || products.stream().anyMatch(p -> !Objects.equals(p.getScoreStatus(),
                        scores.productRecommendScore.get(p.getId()) == null ? SupplierScoreStatus.NOT_READY.name() : SupplierScoreStatus.READY.name())));
        if (result.isChanged()) {
            // 4.1 供应商综合分变化
            persistSupplierScores(supplier, scores);
            // 4.2 产品推荐分变化
            persistProductScores(products, scores);
        }
        // 5. 写日志
        appendChanges(result, ctx);
    }

    /**
     * 单个供货产品报价调整后重算。
     * <p>重算：该供货产品价格分及推荐分、供应商价格汇总及综合分。</p>
     * <p>不重算：供应商质量分/交付分/服务分、其他供货产品（不查180天事实）。</p>
     *
     * @param supplierProductId 触发报价调整的供货产品 ID
     * @param reason 本次报价调整原因，清空报价时仍保留此原因
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public void recalcForQuoteChange(Long supplierProductId, String reason) {
        SupplierProduct sp = supplierProductMapper.selectById(supplierProductId);
        if (sp == null) {
            log.warn("recalcForQuoteChange 供货关系不存在 supplierProductId={}", supplierProductId);
            return;
        }
        RecalcContext ctx = RecalcContext.builder()
                .triggerType(TriggerType.PRICE_TRIGGER)
                .relatedSources(List.of(new ScoreChangeSource(ScoreSourceBusinessType.SUPPLIER_PRODUCT,
                        supplierProductId.toString(), null)))
                .operatorType(OperatorType.USER.name())
                .reason(reason)
                .build();
        recalculatePriceForSupplier(sp.getSupplierId(), Set.of(supplierProductId), ctx);
    }

    /**
     * 产品参考采购价变更后重算（跨供应商）。
     * <p>重算：所有供应商下该产品的供货价格分及推荐分、各供应商价格汇总及综合分。</p>
     * <p>不重算：供应商质量分/交付分/服务分（不查180天事实）。</p>
     * <p>按供应商ID升序取得行锁，防止死锁。</p>
     *
     * @param productId 参考采购价发生变化的产品 ID
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public void recalcForProductReferencePriceChange(Long productId) {
        // 来源编码来自产品本身，不能借用供货关系或采购单编号。
        Product referenceProduct = productMapper.selectById(productId);
        if (referenceProduct == null) {
            throw new BizException(ErrorCode.DATA_NOT_FOUND.getCode(), "参考价产品不存在或已删除");
        }
        // 1. 查询所有涉及该产品的供货关系
        List<SupplierProduct> affected = supplierProductMapper.selectList(new LambdaQueryWrapper<SupplierProduct>()
                .eq(SupplierProduct::getProductId, productId));
        List<Long> supplierIds = affected.stream().map(SupplierProduct::getSupplierId).distinct().sorted().toList();
        // 先取得全部供应商行锁，再读取并算写；仅排序而不取锁不能保护无变化分支。
        for (Long supplierId : supplierIds) {
            if (supplierMapper.lockByIdForUpdate(supplierId) == null) {
                throw new BizException(ErrorCode.DATA_NOT_FOUND.getCode(), "供应商不存在或已删除");
            }
        }
        for (Long supplierId : supplierIds) {
            Set<Long> ids = new HashSet<>();
            // 2. 筛选出该供应商下所有涉及该产品的供货关系
            for (SupplierProduct sp : affected) {
                if (supplierId.equals(sp.getSupplierId())) {
                    ids.add(sp.getId());
                }
            }
            // 3. 重算该供应商下所有涉及该产品的供货关系价格
            RecalcContext ctx = RecalcContext.builder()
                    .triggerType(TriggerType.PRICE_TRIGGER)
                    .relatedSources(List.of(new ScoreChangeSource(ScoreSourceBusinessType.PRODUCT,
                            productId.toString(), referenceProduct.getProductCode())))
                    .operatorType(OperatorType.USER.name())
                    .reason("产品参考采购价调整")
                    .build();
            recalculatePriceForSupplier(supplierId, ids, ctx);
        }
    }

    /**
     * 报价到期扫描时重算。
     * <p>重算：该供应商全部供货产品价格分及推荐分、供应商价格汇总及综合分。</p>
     * <p>不重算：供应商质量分/交付分/服务分（不查180天事实）。</p>
     *
     * @param supplierId 目标供应商 ID
     */
    @Override
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.REPEATABLE_READ)
    public void recalcPricesForSupplier(Long supplierId) {
        // 定时任务只负责外层 Redis 锁；行锁随本方法事务持有，先于任何普通查询。
        if (supplierMapper.lockByIdForUpdate(supplierId) == null) {
            throw new BizException(ErrorCode.DATA_NOT_FOUND.getCode(), "供应商不存在或已删除");
        }
        RecalcContext ctx = RecalcContext.builder()
                .triggerType(TriggerType.QUOTE_EXPIRED_TRIGGER)
                .operatorType(OperatorType.SYSTEM.name())
                .operatorName("报价到期扫描")
                .reason("报价到期校正")
                .build();
        recalculatePriceForSupplier(supplierId, null, ctx);
    }

    /** 价格触发路径只修改受影响的供货产品，供应商价格汇总使用所有有效权重。 */
    private void recalculatePriceForSupplier(Long supplierId, Set<Long> targetIds, RecalcContext ctx) {
        // 1. 获取供应商
        Supplier supplier = supplierMapper.selectById(supplierId);
        if (supplier == null){
            return;
        }
        // 2. 获取供应商下所有供货产品
        List<SupplierProduct> products = supplierProductMapper.selectList(
                new LambdaQueryWrapper<SupplierProduct>().eq(SupplierProduct::getSupplierId, supplierId)
                        .orderByAsc(SupplierProduct::getId));
        if (ctx.getTriggerType() == TriggerType.QUOTE_EXPIRED_TRIGGER) {
            LocalDate businessDate = LocalDate.now(ZoneId.of("Asia/Shanghai"));
            // 仅记录真正到期且仍有旧价格分的关系，不把未来报价混入本次来源。
            ctx.setRelatedSources(products.stream().filter(p -> p.getPriceScore() != null
                            && p.getQuoteValidUntil() != null && p.getQuoteValidUntil().isBefore(businessDate))
                    .map(p -> new ScoreChangeSource(ScoreSourceBusinessType.SUPPLIER_PRODUCT,
                            p.getId().toString(), null)).toList());
        }
        Set<Long> productIds = new HashSet<>();
        // 3. 过滤出受影响的产品ID
        for (SupplierProduct p : products) {
            if (targetIds == null || targetIds.contains(p.getId())){
                productIds.add(p.getProductId());
            }
        }
        // 4. 获取受影响的产品详情
        Map<Long, Product> references = new HashMap<>();
        if (!productIds.isEmpty()) {
            for (Product product : productMapper.selectByIds(productIds)) {
                references.put(product.getId(), product);
            }
        }
        // 5. 根据锁后读取的快照计算价格与推荐分，质量、交付分保留原值。
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        CalculatedScores scores = new CalculatedScores();
        scores.deliveryScore = supplier.getDeliveryScore();
        scores.supplierQualityScore = supplier.getQualityScore();
        ScoreRecalcResult result = ScoreRecalcResult.builder().supplierId(supplierId)
                .metricChanges(new ArrayList<>()).build();
        for (SupplierProduct p : products) {
            Integer price = p.getPriceScore();
            Integer recommend = p.getRecommendScore();
            if (targetIds == null || targetIds.contains(p.getId())) {
                // 5.1 计算受影响的产品价格分
                price = calculateProductPrice(p, references.get(p.getProductId()), today);
                // 5.2 计算受影响的产品推荐分
                recommend = aggregateCalculator.calculateRecommendScore(price, supplier.getDeliveryScore(),
                        p.getQualityScore(), supplier.getServiceScore());
                // 5.3 记录受影响的产品价格分与推荐分变化。
                addIfChanged(result.getMetricChanges(), MetricType.PRICE, p.getId(),
                        p.getPriceScore(), price, p.getRecommendScore(), recommend, null, null);
            }
            // 5.4 更新 scores 中的产品价格、质量、推荐分
            scores.productPriceScore.put(p.getId(), price);
            scores.productQualityScore.put(p.getId(), p.getQualityScore());
            scores.productRecommendScore.put(p.getId(), recommend);
        }
        // 6. 计算供应商价格汇总与衍生评分
        List<SupplierPricePair> pairs = new ArrayList<>();
        for (SupplierProduct p : products) {
            Integer price = scores.productPriceScore.get(p.getId());
            Long amount = p.getScoreBasisAmount();
            if (Integer.valueOf(1).equals(p.getStatus()) && price != null && amount != null && amount > 0) {
                pairs.add(new SupplierPricePair(amount, price));
            }
        }
        // 6.1 计算供应商价格分
        scores.supplierPriceScore = aggregateCalculator.aggregateSupplierPrice(pairs);
        // 6.2 计算供应商总分
        scores.overallScore = aggregateCalculator.calculateOverallScore(scores.supplierPriceScore,
                supplier.getDeliveryScore(), supplier.getQualityScore(), supplier.getServiceScore());
        // 6.3 添加供应商价格分、总分进入结果列表
        addIfChanged(result.getMetricChanges(), MetricType.PRICE, null,
                supplier.getPriceScore(), scores.supplierPriceScore, null, null,
                supplier.getOverallScore(), scores.overallScore);
        // 6.4 检查供应商评分状态是否变化
        result.setChanged(!result.getMetricChanges().isEmpty()
            || !Objects.equals(supplier.getScoreStatus(), scores.overallScore == null ? SupplierScoreStatus.NOT_READY.name() : SupplierScoreStatus.READY.name())
            || products.stream().anyMatch(p -> (targetIds == null || targetIds.contains(p.getId()))
                && !Objects.equals(p.getScoreStatus(), scores.productRecommendScore.get(p.getId()) == null
                ? SupplierScoreStatus.NOT_READY.name() : SupplierScoreStatus.READY.name())));
        // 6.5 变化则更新数据库数据。
        if (result.isChanged()) {
            persistSupplierScores(supplier, scores);
            persistProductScores(products, scores);
        }
        // 6.6 写变化日志。
        appendChanges(result, ctx);
    }

    /** 无效、停用或到期报价没有价格分；参考价以元存储，换算为分后计算。 */
    private Integer calculateProductPrice(SupplierProduct relation, Product product, LocalDate date) {
        if (!Integer.valueOf(1).equals(relation.getStatus()) || relation.getQuoteValidUntil() == null
                || relation.getQuoteValidUntil().isBefore(date) || product == null
                || product.getReferencePurchasePrice() == null){
            return null;
        }
        long referenceCents = product.getReferencePurchasePrice().movePointRight(2)
                .setScale(0, RoundingMode.HALF_UP).longValueExact();
        return priceCalculator.calculate(referenceCents, relation.getQuotedPurchasePrice());
    }

    // ============================================
    // 异步入口(MQ Consumer / 定时任务)
    // ============================================

    /**
     * 完全入库触发的事实评分重算（业务日期取上海时区当天）。
     * <p>重算：供应商质量分/价格汇总/综合分、受影响供货产品质量分/价格分/推荐分。</p>
     * <p>交付分：正常入库沿用已有，缺失时补查；全量校正时重算。</p>
     * <p>不重算：人工服务分（保留原值）。</p>
     *
     * @param context 重算上下文，包含供应商、触发来源及操作信息
     * @return 评分变化结果及对应指标变化明细
     */
    @Override
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.REPEATABLE_READ)
    public ScoreRecalcResult recalcForSupplier(ScoreRecalcContext context) {
        return recalcFactsForSupplier(context, LocalDate.now(ZoneId.of("Asia/Shanghai")));
    }

    /**
     * 事实评分重算（核心入口，指定业务日期）。
     * <p>重算：供应商质量分/价格汇总/综合分、受影响供货产品质量分/价格分/推荐分。</p>
     * <p>交付分：正常入库沿用已有，缺失时补查；冷启动/每日校正/异常回退时全量重算。</p>
     * <p>不重算：人工服务分（保留原值）。</p>
     * <p>正常入库只查受影响产品质量窗口，增量追加质量总额；</p>
     * <p>冷启动/跨日/异常回退/每日校正完整重建180天事实缓存，交付分也全量重算。</p>
     *
     * @param context 重算上下文，包含供应商、触发来源及操作信息
     * @param businessDate 评分窗口及逾期计算使用的业务日期
     * @return 评分变化结果；无有效变化时变化标记为 false
     */
    @Override
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.REPEATABLE_READ)
    public ScoreRecalcResult recalcFactsForSupplier(ScoreRecalcContext context, LocalDate businessDate) {
        Long supplierId = context.getSupplierId();
        // 外层已持 Redis 锁；
        // 1. 先取得数据库行锁，再建立可重复读快照，避免等待后仍读旧事实。
        if (supplierMapper.lockByIdForUpdate(supplierId) == null) {
            throw new BizException(ErrorCode.DATA_NOT_FOUND.getCode(), "供应商不存在或已删除");
        }
        // 2. 转换为日志上下文，包含供应商、触发来源及操作信息
        RecalcContext logContext = legacyFromScoreContext(context);
        FactsSnapshot facts;
        // 3. 根据触发类型选择事实查询方式
        if (context.getTriggerType() == TriggerType.INBOUND_TRIGGER) {
            List<Long> orderIds = context.getCompletedOrderIds();
            // 入库计算范围只能来自明确的订单集合，日志来源不能代替，也不能缺失后静默全量重算。
            if (orderIds == null || orderIds.isEmpty()) {
                throw new BizException(ErrorCode.PARAM_ERROR.getCode(), "入库评分重算必须提供非空的完成采购单集合");
            }
            // 3.1 正常入库只查询受影响产品质量与供应商缓存质量总额；冷启动等场景返回全量事实。
            facts = factsQueryService.queryInboundFacts(supplierId, businessDate, orderIds);
        } else {
            // 每日校正强制重建完整窗口，处理移出窗口、消息失败及业务日变化。
            facts = factsQueryService.queryFacts(supplierId, businessDate);
        }
        // 4. 计算并持久化评分变化
        ScoreRecalcResult result = recalculateAndPersist(supplierId, businessDate, facts);
        // 5. 写变化日志
        appendChanges(result, logContext);
        return result;
    }

    // ============================================
    // 通用变化日志与事实评分持久化
    // ============================================

    /**
     * 为各重算入口追加实际指标变化日志；MQ 沿用预分配的 SC 批次，无批次时生成同格式业务编号。
     */
    private void appendChanges(ScoreRecalcResult result, RecalcContext ctx) {
        if (result.isChanged() && result.getMetricChanges() != null && !result.getMetricChanges().isEmpty()) {
            // 同步与每日校正有实际变化才取号；MQ 已在首窗口预分配，重投沿用原批次参与日志幂等。
            if (ctx.getBatchNo() == null){
                ctx.setBatchNo(billNoGenerator.nextNo(SupplierScoreRedisKeys.SC_BILL_PREFIX,
                        changeLogMapper::findMaxBatchNoSequence));
            }
            changeLogService.appendBatch(toScoreChangeBatchCommand(result, ctx));
        }
    }

    /** 按指定业务日期的事实计算评分并持久化变化，同时修正评分状态。 */
    private ScoreRecalcResult recalculateAndPersist(Long supplierId, LocalDate date, FactsSnapshot facts) {
        // 1. 读 supplier 当前快照
        Supplier supplier = supplierMapper.selectById(supplierId);
        if (supplier == null) {
            log.warn("doRecalc 供应商不存在 supplierId={}", supplierId);
            return ScoreRecalcResult.builder().supplierId(supplierId).changed(false).build();
        }
        Map<String, Integer> supplierBefore = new HashMap<>();
        supplierBefore.put("deliveryScore", supplier.getDeliveryScore());
        supplierBefore.put("qualityScore", supplier.getQualityScore());
        supplierBefore.put("priceScore", supplier.getPriceScore());
        supplierBefore.put("serviceScore", supplier.getServiceScore());
        supplierBefore.put("overallScore", supplier.getOverallScore());
        // 2. 读 supplier_product 列表
        List<SupplierProduct> products = supplierProductMapper.selectList(
                new LambdaQueryWrapper<SupplierProduct>().eq(SupplierProduct::getSupplierId, supplierId));
        // 3. 计算对应的供应商评分和产品评分列表
        CalculatedScores calculated = calculateScores(supplier, products, facts, date);
        // 4. 对比并收集变化
        ScoreRecalcResult result = ScoreRecalcResult.builder()
                .supplierId(supplierId)
                .build();
        result.setChanged(false);
        // 4.1 对比供应商交付、质量、价格及综合分；人工服务分不在此处修改
        compareAndCollectSupplier(supplier, result, supplierBefore, calculated);
        // 4.2 对比产品价格、质量及推荐分
        compareAndCollectProducts(result, products, calculated);
        if (!Objects.equals(supplier.getScoreStatus(), calculated.overallScore == null ? SupplierScoreStatus.NOT_READY.name() : SupplierScoreStatus.READY.name())
                || products.stream().anyMatch(p -> !Objects.equals(p.getScoreStatus(),
                calculated.productRecommendScore.get(p.getId()) == null ? SupplierScoreStatus.NOT_READY.name() : SupplierScoreStatus.READY.name()))) {
            result.setChanged(true);
        }
        // 5. 变化时持久化，未涉及的产品保持原值
        if (result.isChanged()) {
            persistSupplierScores(supplier, calculated);
            persistProductScores(products, calculated);
            log.info("重算完成 supplierId={} changed={} metricChanges={}",
                    supplierId, true, result.getMetricChanges() == null ? 0 : result.getMetricChanges().size());
        }
        return result;
    }

    // ---------- 计算辅助 ----------

    /**
     * 评分聚合结果(中间结构)。
     */
    private static final class CalculatedScores {
        Integer supplierPriceScore;        // 供应商级价格汇总
        Integer supplierQualityScore;      // 供应商级质量汇总
        Integer deliveryScore;             // 供应商级
        Integer overallScore;              // 供应商级
        // SP 级(按 supplierProductId 索引)
        Map<Long, Integer> productPriceScore = new HashMap<>();      // SP 自身价格分
        Map<Long, Integer> productQualityScore = new HashMap<>();    // SP 自身质量分
        Map<Long, Integer> productRecommendScore = new HashMap<>();  // SP 推荐分(价格×30% + 交付×30% + 质量×30% + 服务×10%)
    }

    /**
     * 以事实金额计算范围内的质量、价格及供应商综合分。
     * 正常入库沿用已有交付分，交付分缺失时仅补查交付事实；全量校正使用快照中的交付金额。
     * 交付变化或全量校正更新全部推荐分，否则只更新本批涉及产品。
     */
    private CalculatedScores calculateScores(Supplier supplier, List<SupplierProduct> products,
                                             FactsSnapshot facts, LocalDate date) {
        CalculatedScores c = new CalculatedScores();
        // 三个 Map 必须包含完整产品快照，未查询的产品不能被持久化方法误写成 null。
        for (SupplierProduct product : products) {
            c.productPriceScore.put(product.getId(), product.getPriceScore());
            c.productQualityScore.put(product.getId(), product.getQualityScore());
            c.productRecommendScore.put(product.getId(), product.getRecommendScore());
        }
        // 1. 正常入库沿用已落库交付分；每日、冷启动及异常回退使用真实窗口事实校正。
        if (facts.isNeedDeliveryRecalculation()) {
            c.deliveryScore = facts.getPenaltyAmountRaw() == null
                    ? deliveryCalculator.calculate(facts.getDueAmount(), facts.getPenaltyAmount())
                    : deliveryCalculator.calculateAmounts(facts.getDueAmount(), facts.getPenaltyAmountRaw());
        } else if (supplier.getDeliveryScore() == null) {
            // null 可能是未初始化或没有有效样本，只补查交付事实，不能填零或重新扫描全部质量历史。
            var delivery = factsAggregationService.queryDeliveryFacts(supplier.getId(), date);
            c.deliveryScore = deliveryCalculator.calculateAmounts(delivery.dueAmountCents(), delivery.penaltyAmountRaw());
        } else {
            c.deliveryScore = supplier.getDeliveryScore();
        }
        // 2. 供应商级质量分计算
        c.supplierQualityScore = qualityCalculator.calculateAmounts(
                facts.getQualifiedAmountRaw(), facts.getDefectiveAmountRaw());
        // 3. 供应商级价格分计算
        Map<Long, Product> referenceProducts = new HashMap<>();
        Set<Long> productIds = new HashSet<>();
        // 3.1 只为本次涉及的供货关系重算价格，其余价格分参与供应商加权时沿用数据库值
        for (SupplierProduct p : products) {
            if (facts.isFullQualityRebuild() || facts.getAffectedSupplierProductIds().contains(p.getId())) {
                productIds.add(p.getProductId());
            }
        }
        // 3.2 查询影响的产品的事实
        if (!productIds.isEmpty()) {
            for (Product product : productMapper.selectByIds(productIds)) {
                referenceProducts.put(product.getId(), product);
            }
        }
        // 4. 只为本次涉及的供货关系重算价格，其余价格分参与供应商加权时沿用数据库值。
        for (SupplierProduct p : products) {
            Integer price = p.getPriceScore();
            // 4.1 若是影响的产品,则计算对应的价格分
            if (facts.isFullQualityRebuild() || facts.getAffectedSupplierProductIds().contains(p.getId())) {
                Product product = referenceProducts.get(p.getProductId());
                Long referenceCents = product == null || product.getReferencePurchasePrice() == null ? null
                        : product.getReferencePurchasePrice().movePointRight(2)
                                .setScale(0, RoundingMode.HALF_UP).longValueExact();
                boolean validQuote = Integer.valueOf(1).equals(p.getStatus())
                        && p.getQuoteValidUntil() != null && !p.getQuoteValidUntil().isBefore(date);
                price = validQuote ? priceCalculator.calculate(referenceCents, p.getQuotedPurchasePrice()) : null;
            }
            c.productPriceScore.put(p.getId(), price);
            // 4.2 若是影响的产品,则计算对应的质量分
            Integer quality = p.getQualityScore();
            if (facts.isFullQualityRebuild() || facts.getAffectedSupplierProductIds().contains(p.getId())) {
                var amount = facts.getProductQualityAmounts().get(p.getId());
                quality = amount == null ? null : qualityCalculator.calculateAmounts(
                        amount.qualifiedRaw(), amount.defectiveRaw());
            }
            c.productQualityScore.put(p.getId(), quality);
        }
        // 5. 形成供货产品价格列表(basis, price)
        List<SupplierPricePair> pairs = new ArrayList<>();
        for (SupplierProduct p : products) {
            Integer price = c.productPriceScore.get(p.getId());
            Long basis = p.getScoreBasisAmount();
            if (Integer.valueOf(1).equals(p.getStatus()) && price != null && basis != null && basis > 0) {
                pairs.add(new SupplierPricePair(basis, price));
            }
        }
        // 5.1 计算供应商级价格分
        c.supplierPriceScore = aggregateCalculator.aggregateSupplierPrice(pairs);
        Integer serviceScore = supplier.getServiceScore();
        // 5. 供应商级综合分计算
        c.overallScore = aggregateCalculator.calculateOverallScore(
                c.supplierPriceScore, c.deliveryScore, c.supplierQualityScore, serviceScore);
        // 6. 根据事实覆盖范围及交付变化计算产品推荐分
        for (SupplierProduct p : products) {
            if (!facts.isFullQualityRebuild() && Objects.equals(supplier.getDeliveryScore(), c.deliveryScore)
                    && !facts.getAffectedSupplierProductIds().contains(p.getId()))
            {
                continue;
            }
            Integer price = c.productPriceScore.get(p.getId());
            Integer quality = c.productQualityScore.get(p.getId());
            Integer recommend = aggregateCalculator.calculateRecommendScore(
                    price, c.deliveryScore, quality, serviceScore);
            c.productRecommendScore.put(p.getId(), recommend);
        }
        return c;
    }

    // ---------- 对比 + 收集变化 ----------

    /** 收集真实基础指标变化，综合分变化不能让未变化的其他指标被误记为变更。 */
    private void compareAndCollectSupplier(Supplier supplier,
                                           ScoreRecalcResult result,
                                           Map<String, Integer> before,
                                           CalculatedScores calc) {
        List<ScoreRecalcResult.MetricChange> changes = new ArrayList<>();
        // 1. 供应商级交付分变化
        if (!Objects.equals(before.get("deliveryScore"), calc.deliveryScore)) {
            addIfChanged(changes, MetricType.DELIVERY, null,
                    before.get("deliveryScore"), calc.deliveryScore,
                    null, null,
                    before.get("overallScore"), calc.overallScore);
        }
        // 2. 供应商级质量分变化
        if (!Objects.equals(before.get("qualityScore"), calc.supplierQualityScore)) {
            addIfChanged(changes, MetricType.QUALITY, null,
                    before.get("qualityScore"), calc.supplierQualityScore,
                    null, null,
                    before.get("overallScore"), calc.overallScore);
        }
        // 3. 供应商级价格分变化
        if (!Objects.equals(before.get("priceScore"), calc.supplierPriceScore)) {
            addIfChanged(changes, MetricType.PRICE, null,
                    before.get("priceScore"), calc.supplierPriceScore,
                    null, null,
                    before.get("overallScore"), calc.overallScore);
        }
        // 事实校正保留人工服务分。仅综合分公式纠错时保留一条事实校正日志，不伪造基础分变化。
        if (changes.isEmpty() && !Objects.equals(before.get("overallScore"), calc.overallScore)) {
            addIfChanged(changes, MetricType.QUALITY, null,
                    before.get("qualityScore"), calc.supplierQualityScore, null, null,
                    before.get("overallScore"), calc.overallScore);
        }
        // 综合分随本次范围内的指标变化记录，不额外生成独立指标。
        if (!changes.isEmpty()) {
            result.setChanged(true);
            result.setMetricChanges(changes);
        }
    }

    /**
     * 基础指标、产品推荐分或供应商综合分任一变化时追加一条评分变化明细。
     */
    private void addIfChanged(List<ScoreRecalcResult.MetricChange> changes,
                              MetricType type, Long spId,
                              Integer metricBefore, Integer metricAfter,
                              Integer recBefore, Integer recAfter,
                              Integer overallBefore, Integer overallAfter) {
        if (Objects.equals(metricBefore, metricAfter) && Objects.equals(recBefore, recAfter)
                && Objects.equals(overallBefore, overallAfter)) {
            return;
        }
        ScoreRecalcResult.MetricChange m = ScoreRecalcResult.MetricChange.builder()
                .metricType(type)
                .supplierProductId(spId)
                .metricScoreBefore(metricBefore)
                .metricScoreAfter(metricAfter)
                .productRecommendScoreBefore(recBefore)
                .productRecommendScoreAfter(recAfter)
                .supplierOverallScoreBefore(overallBefore)
                .supplierOverallScoreAfter(overallAfter)
                .build();
        changes.add(m);
    }

    /** 收集产品基础指标和推荐分变化，避免仅推荐分变化时漏记日志。 */
    private void compareAndCollectProducts(ScoreRecalcResult result,
                                            List<SupplierProduct> products,
                                            CalculatedScores calc) {
        List<ScoreRecalcResult.MetricChange> productChanges = result.getMetricChanges();
        // 1. 产品基础指标变化
        if (productChanges == null) {
            productChanges = new ArrayList<>();
            result.setMetricChanges(productChanges);
        }
        // 2. 产品推荐分变化
        for (SupplierProduct p : products) {
            // 2.1 判断是否需要对比该指标类型
            Integer recommendBefore = p.getRecommendScore();
            Integer recommendAfter = calc.productRecommendScore.get(p.getId());
            int previousSize = productChanges.size();
            if (!Objects.equals(p.getQualityScore(), calc.productQualityScore.get(p.getId()))) {
                addIfChanged(productChanges, MetricType.QUALITY, p.getId(),
                        p.getQualityScore(), calc.productQualityScore.get(p.getId()),
                        recommendBefore, recommendAfter, null, null);
            }
            if (!Objects.equals(p.getPriceScore(), calc.productPriceScore.get(p.getId()))) {
                addIfChanged(productChanges, MetricType.PRICE, p.getId(),
                        p.getPriceScore(), calc.productPriceScore.get(p.getId()),
                        recommendBefore, recommendAfter, null, null);
            }
            if (productChanges.size() == previousSize && !Objects.equals(recommendBefore, recommendAfter)) {
                // 没有产品基础分变化时，这是供应商交付影响或全量推荐公式校正，不是产品质量/价格变化。
                addIfChanged(productChanges, MetricType.DELIVERY, p.getId(), null, null,
                        recommendBefore, recommendAfter, null, null);
            }
            if (productChanges.size() != previousSize) result.setChanged(true);
        }
    }

    // ---------- 持久化 ----------

    /** 显式更新供应商质量、交付、价格、综合分及状态，并通过版本条件防止并发覆盖。 */
    private void persistSupplierScores(Supplier supplier, CalculatedScores calc) {
        String status = calc.overallScore == null ? SupplierScoreStatus.NOT_READY.name() : SupplierScoreStatus.READY.name();
        // 1. 判断是否有指标变化
        if (Objects.equals(supplier.getDeliveryScore(), calc.deliveryScore)
                && Objects.equals(supplier.getQualityScore(), calc.supplierQualityScore)
                && Objects.equals(supplier.getPriceScore(), calc.supplierPriceScore)
                && Objects.equals(supplier.getOverallScore(), calc.overallScore)
                && Objects.equals(supplier.getScoreStatus(), status)) {
            return;
        }
        // 2. 更新供应商指标
        LambdaUpdateWrapper<Supplier> update = new LambdaUpdateWrapper<Supplier>()
                .eq(Supplier::getId, supplier.getId())
                .set(Supplier::getDeliveryScore, calc.deliveryScore)
                .set(Supplier::getQualityScore, calc.supplierQualityScore)
                .set(Supplier::getPriceScore, calc.supplierPriceScore)
                .set(Supplier::getOverallScore, calc.overallScore)
                .set(Supplier::getScoreStatus, status);
        if (supplier.getVersion() != null) {
            update.eq(Supplier::getVersion, supplier.getVersion()).set(Supplier::getVersion, supplier.getVersion() + 1);
        }
        if (supplierMapper.update(null, update) != 1) {
            throw new BizException(ErrorCode.OPERATION_FAILED.getCode(), "供应商评分已发生变化，请重试");
        }
    }

    /** 仅更新发生变化的产品质量、价格、推荐分及状态，允许清空失效的旧分数。 */
    private void persistProductScores(List<SupplierProduct> products, CalculatedScores calc) {
        for (SupplierProduct p : products) {
            Integer newQuality = calc.productQualityScore.get(p.getId());
            Integer newPrice = calc.productPriceScore.get(p.getId());
            Integer newRecommend = calc.productRecommendScore.get(p.getId());
            if (Objects.equals(p.getQualityScore(), newQuality) && Objects.equals(p.getPriceScore(), newPrice)
                    && Objects.equals(p.getRecommendScore(), newRecommend)
                    && Objects.equals(p.getScoreStatus(), newRecommend == null ? SupplierScoreStatus.NOT_READY.name() : SupplierScoreStatus.READY.name())) {
                continue;
            }
            // 显式 SET 可写 NULL，避免默认 NOT_NULL 策略保留已经出窗的旧分数。
            LambdaUpdateWrapper<SupplierProduct> update = new LambdaUpdateWrapper<SupplierProduct>()
                    .eq(SupplierProduct::getId, p.getId()).set(SupplierProduct::getQualityScore, newQuality)
                    .set(SupplierProduct::getPriceScore, newPrice)
                    .set(SupplierProduct::getRecommendScore, newRecommend)
                    .set(SupplierProduct::getScoreStatus, newRecommend == null ? SupplierScoreStatus.NOT_READY.name() : SupplierScoreStatus.READY.name());
            if (p.getVersion() != null) {
                update.eq(SupplierProduct::getVersion, p.getVersion()).set(SupplierProduct::getVersion, p.getVersion() + 1);
            }
            if (supplierProductMapper.update(null, update) != 1) {
                throw new BizException(ErrorCode.OPERATION_FAILED.getCode(), "供货产品评分已发生变化，请重试");
            }
        }
    }

    // ---------- 事实重算上下文转为日志元数据(ScoreRecalcContext → RecalcContext) ----------

    /** 将统一重算上下文转换为共享计算和日志构造使用的兼容上下文。 */
    private RecalcContext legacyFromScoreContext(ScoreRecalcContext scoreCtx) {
        return RecalcContext.builder()
                .batchNo(scoreCtx.getBatchNo())
                .triggerType(scoreCtx.getTriggerType())
                .relatedSources(scoreCtx.getRelatedSources())
                .operatorType(scoreCtx.getOperatorType())
                .operatorId(scoreCtx.getOperatorId())
                .operatorName(scoreCtx.getOperatorName())
                .reason(scoreCtx.getMergedReason())
                .build();
    }

    // ---------- 评分日志写入命令构造 ----------

    /**
     * 评分日志写入命令构造。
     *
     * <p>各重算入口共用日志结构，事务由其调用方负责。</p>
     */
    private ScoreChangeBatchCommand toScoreChangeBatchCommand(ScoreRecalcResult result, RecalcContext ctx) {
        ScoreChangeBatchCommand cmd = new ScoreChangeBatchCommand();
        // 1. 共有属性赋值
        cmd.setBatchNo(ctx.getBatchNo());
        cmd.setRuleVersion(SupplierScoreConstants.RULE_VERSION_V2);
        cmd.setSupplierId(result.getSupplierId());
        cmd.setTriggerType(ctx.getTriggerType());
        cmd.setRelatedSources(ctx.getRelatedSources() == null ? List.of() : ctx.getRelatedSources());
        cmd.setOperatorType(ctx.getOperatorType());
        cmd.setOperatorId(ctx.getOperatorId());
        cmd.setOperatorName(ctx.getOperatorName());
        // 人工身份只在实际写变化日志时读取；系统消费者不得冒充当前用户。
        if (OperatorType.USER.name().equals(ctx.getOperatorType()) && ctx.getOperatorId() == null) {
            var user = UserContext.requireCurrentUser();
            cmd.setOperatorId(user.getUserId());
            cmd.setOperatorName(user.getRealName());
        }
        // 2. 构建原因描述
        if (ctx.getReason() == null) {
            StringBuilder sb = new StringBuilder();
            sb.append("重算触发类型:").append(ctx.getTriggerType());
            cmd.setReason(sb.toString());
        } else {
            cmd.setReason(ctx.getReason());
        }
        // 3. 构建日志条目
        List<ScoreChangeLogEntry> entries = new ArrayList<>();
        if (result.getMetricChanges() != null) {
            for (ScoreRecalcResult.MetricChange m : result.getMetricChanges()) {
                ScoreChangeLogEntry entry = new ScoreChangeLogEntry();
                entry.setMetricType(m.getMetricType());
                entry.setSupplierProductId(m.getSupplierProductId());
                entry.setMetricScoreBefore(m.getMetricScoreBefore());
                entry.setMetricScoreAfter(m.getMetricScoreAfter());
                entry.setProductRecommendScoreBefore(m.getProductRecommendScoreBefore());
                entry.setProductRecommendScoreAfter(m.getProductRecommendScoreAfter());
                entry.setSupplierOverallScoreBefore(m.getSupplierOverallScoreBefore());
                entry.setSupplierOverallScoreAfter(m.getSupplierOverallScoreAfter());
                entries.add(entry);
            }
        }
        cmd.setEntries(entries);
        return cmd;
    }
}