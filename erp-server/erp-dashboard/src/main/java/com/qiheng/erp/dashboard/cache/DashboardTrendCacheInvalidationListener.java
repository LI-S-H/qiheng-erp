package com.qiheng.erp.dashboard.cache;

import com.qiheng.erp.common.event.dashboard.DashboardTrendInvalidatedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** 仅在业务事务提交成功后失效对应日期；事务回滚不会污染趋势缓存。 */
@Component
@RequiredArgsConstructor
public class DashboardTrendCacheInvalidationListener {

    private final DashboardTrendDailyAmountRefreshService trendDailyAmountRefreshService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void invalidate(DashboardTrendInvalidatedEvent event) {
        trendDailyAmountRefreshService.invalidate(event.metric(), event.businessDate());
    }
}