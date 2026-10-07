package com.qiheng.erp.purchase.domain.supplierscore.dto;

import lombok.Data;
import com.qiheng.erp.purchase.service.scoring.SupplierScoreFactsAggregationService.QualityAmount;
import java.math.BigInteger;
import java.math.BigDecimal;

import java.util.Map;
import java.util.Set;

/**
 * 两个独立窗口的评分事实，可来自数据库全量汇总或缓存与局部查询组合。
 * 产品质检金额只保留于本次计算快照，不缓存到 Redis；覆盖范围决定哪些产品允许更新。
 */
@Data
public class FactsSnapshot {
    /** 合格金额原始整数（数量存储值乘单价分）。 */
    private BigInteger qualifiedAmountRaw;
    /** 不合格金额原始整数，单位与合格金额一致。 */
    private BigInteger defectiveAmountRaw;
    /** 按供货产品 ID 汇总的质量金额，仅用于本次质量分计算。 */
    private Map<Long, QualityAmount> productQualityAmounts = Map.of();
    /** 本次实际查询质量事实的产品；集合内无有效金额应清空分数，集合外保留已有分数。 */
    private Set<Long> affectedSupplierProductIds = Set.of();
    /** 冷启动和每日校正覆盖全部产品；默认完整快照，白天部分查询必须明确设为 false。 */
    private boolean fullQualityRebuild = true;
    /** 全量事实重新计算交付分；正常入库局部事实设为 false，沿用供应商已落库交付分。 */
    private boolean needDeliveryRecalculation = true;
    /** 因历史数据异常跳过的质量订单数量。 */
    private long skippedQualityOrders;
    /** 因历史数据异常跳过的交付订单数量。 */
    private long skippedDeliveryOrders;
    /** 目标供应商 ID */
    private Long supplierId;
    /** 180 天到期应交金额合计(分) */
    private Long dueAmount;
    /** 已乘逾期系数并舍入到分的展示罚额，不用于评分计算。 */
    private Long penaltyAmount;
    /** 含亚分精度的加权罚额，评分使用此字段，不使用舍入后的展示罚额。 */
    private BigDecimal penaltyAmountRaw;
    /** 完整数据库来源为 DB，白天缓存与局部数据库组合为 CACHE_AND_DB。 */
    private String source;

}
