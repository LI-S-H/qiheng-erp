package com.qiheng.erp.dashboard.domain.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

/**
 * 工作台首屏经营指标稳定编码枚举。
 *
 * <p>每个枚举项自带展示名称、单位、对比文案和指标方向，
 * 彻底杜绝拼写错误，新增指标只需加一行枚举即可。</p>
 */
@Getter
public enum MetricKey {

    MONTH_SALES("本月销售额", "元", "较上月", MetricDirection.POSITIVE),

    MONTH_GROSS_PROFIT("本月毛利额", "元", "较上月", MetricDirection.POSITIVE),

    PENDING_ORDERS("待处理订单", "单", "较昨日", MetricDirection.NEGATIVE),

    STOCK_RISK_SKU("库存风险 SKU", "个", "较昨日", MetricDirection.NEGATIVE);

    private final String label;
    private final String unit;
    private final String compareText;
    private final MetricDirection direction;

    MetricKey(String label, String unit, String compareText, MetricDirection direction) {
        this.label = label;
        this.unit = unit;
        this.compareText = compareText;
        this.direction = direction;
    }

    @JsonValue
    public String toName() {
        return name();
    }

}