package com.qiheng.erp.dashboard.loader;

import com.qiheng.erp.common.mq.SystemExceptionMqPublisher;
import com.qiheng.erp.dashboard.cache.RankDataCorruptedException;
import com.qiheng.erp.dashboard.cache.TopProductRankCache;
import com.qiheng.erp.dashboard.cache.TopProductRankCache.RebuildOutcome;
import com.qiheng.erp.dashboard.cache.TopProductRankRefresher;
import com.qiheng.erp.dashboard.cache.TopRankRebuildFailedException;
import com.qiheng.erp.dashboard.domain.topproduct.vo.DashboardTopProductVO;
import com.qiheng.erp.product.mapper.ProductMapper;
import com.qiheng.erp.warehouse.mapper.WarehouseStockMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证 DashboardTopProductLoader 编排:
 * <ul>
 *   <li>readTop 抛 RankDataCorruptedException → 上报系统异常 + 触发 rebuild + 返回空</li>
 *   <li>冷启动空数据 → 触发 rebuild</li>
 *   <li>rebuild 抛出 SQL 故障 → 不污染业务响应</li>
 *   <li>rebuild SKIPPED_LOCK_BUSY → 业务返回空(其他实例在跑)</li>
 * </ul>
 *
 * <p>多实例并发去重由 rebuild() 内部 Redisson 锁天然完成,本类不再维护 dirty 守卫。
 * 单测不验证 Redisson 锁内部行为(在 IT 中验证)。</p>
 *
 * <p>本测试不覆盖 VO 组装路径:{@code loadProducts} / {@code loadAvailableQty}
 * 使用 LambdaQueryWrapper,在 Spring 容器外构造会触发 MyBatis-Plus lambda cache 异常,
 * 需要 Spring 容器上下文的集成测试覆盖 VO 字段映射与已删除商品过滤逻辑。</p>
 *
 * @author Li
 * @since 2026-10-08
 */
class DashboardTopProductLoaderTest {

    private TopProductRankCache rankCache;
    private TopProductRankRefresher refresher;
    private ProductMapper productMapper;
    private WarehouseStockMapper warehouseStockMapper;
    private SystemExceptionMqPublisher publisher;
    private DashboardTopProductLoader loader;

    @BeforeEach
    void setUp() {
        rankCache = mock(TopProductRankCache.class);
        refresher = mock(TopProductRankRefresher.class);
        productMapper = mock(ProductMapper.class);
        warehouseStockMapper = mock(WarehouseStockMapper.class);
        publisher = mock(SystemExceptionMqPublisher.class);
        loader = new DashboardTopProductLoader(rankCache, refresher,
                productMapper, warehouseStockMapper, publisher);
    }

    @Test
    void shouldTriggerRebuildWhenReadTopThrowsCorrupted() {
        // 脏数据:log.info 记录(不视为错误),触发 rebuild SUCCESS + 返回空
        // 脏数据本身不直接 publishSystemError(可能外部命令临时污染),只有 rebuild 失败才视为真异常
        when(rankCache.readTop(anyInt())).thenThrow(new RankDataCorruptedException("1", "1000", "null"));
        when(rankCache.rebuild(any())).thenReturn(RebuildOutcome.SUCCESS);

        List<DashboardTopProductVO> result = loader.load();

        assertThat(result).isEmpty();
        // 关键:脏数据 + rebuild SUCCESS 不上报(正常恢复路径)
        verify(publisher, never()).publishSystemError(any());
        // 触发 rebuild
        verify(rankCache, times(1)).rebuild(any());
    }

    @Test
    void shouldPublishSystemErrorWhenRebuildFailsOnCorruptedData() {
        // 脏数据 + rebuild 抛 SQL 故障:loader 上报系统异常(errorCode=TopRankRebuildFailedException)
        when(rankCache.readTop(anyInt())).thenThrow(new RankDataCorruptedException("1", "1000", "null"));
        org.mockito.Mockito.doThrow(new RuntimeException("SQL 不可用"))
                .when(rankCache).rebuild(any());

        org.assertj.core.api.Assertions.assertThatCode(() -> loader.load())
                .doesNotThrowAnyException();
        // 关键:rebuild 穿透时 loader 上报系统异常(真异常,需运维介入)
        ArgumentCaptor<RuntimeException> captor = ArgumentCaptor.forClass(RuntimeException.class);
        verify(publisher, times(1)).publishSystemError(captor.capture());
        assertThat(captor.getValue()).isInstanceOf(TopRankRebuildFailedException.class);
    }

    @Test
    void shouldPublishSystemErrorWhenRebuildOutcomeIsFailed() {
        // 脏数据 + rebuild 返回 FAILED(锁等待中断):loader 上报系统异常
        when(rankCache.readTop(anyInt())).thenThrow(new RankDataCorruptedException("1", "1000", "null"));
        when(rankCache.rebuild(any())).thenReturn(RebuildOutcome.FAILED);

        List<DashboardTopProductVO> result = loader.load();

        assertThat(result).isEmpty();
        ArgumentCaptor<RuntimeException> captor = ArgumentCaptor.forClass(RuntimeException.class);
        verify(publisher, times(1)).publishSystemError(captor.capture());
        assertThat(captor.getValue()).isInstanceOf(TopRankRebuildFailedException.class);
    }

    @Test
    void shouldNotPublishSystemErrorWhenRebuildSuccessOnCorruptedData() {
        // 脏数据 + rebuild SUCCESS:数据已修复,只 log.info 不上报(正常恢复路径)
        when(rankCache.readTop(anyInt())).thenThrow(new RankDataCorruptedException("1", "1000", "null"));
        when(rankCache.rebuild(any())).thenReturn(RebuildOutcome.SUCCESS);

        List<DashboardTopProductVO> result = loader.load();

        assertThat(result).isEmpty();
        verify(publisher, never()).publishSystemError(any());
    }

    @Test
    void shouldNotPublishSystemErrorWhenRebuildSkippedLockBusyOnCorruptedData() {
        // 脏数据 + rebuild SKIPPED_LOCK_BUSY(其他实例在跑):loader 不上报(其他实例可能已上报)
        when(rankCache.readTop(anyInt())).thenThrow(new RankDataCorruptedException("1", "1000", "null"));
        when(rankCache.rebuild(any())).thenReturn(RebuildOutcome.SKIPPED_LOCK_BUSY);

        List<DashboardTopProductVO> result = loader.load();

        assertThat(result).isEmpty();
        // SKIPPED 不上报:避免多个实例重复上报同一次脏数据
        verify(publisher, never()).publishSystemError(any());
    }

    @Test
    void shouldTriggerRebuildOnColdStartEmptyCache() {
        // 冷启动:readTop 第一次空 → 触发 rebuild → 再读
        when(rankCache.readTop(anyInt()))
                .thenReturn(List.of())
                .thenReturn(List.of());
        when(rankCache.rebuild(any())).thenReturn(RebuildOutcome.SUCCESS);

        List<DashboardTopProductVO> result = loader.load();

        assertThat(result).isEmpty();
        verify(rankCache, times(2)).readTop(anyInt());
        verify(rankCache, times(1)).rebuild(any());
    }

    @Test
    void shouldNotThrowWhenColdStartRebuildFails() {
        // 冷启动 rebuild 抛异常(首次启动 SQL 不可用等):loader 上报系统异常 + 业务返回空
        // 冷启动与脏数据共用 triggerRebuild,SQL 故障都视为真异常
        when(rankCache.readTop(anyInt())).thenReturn(List.of());
        org.mockito.Mockito.doThrow(new RuntimeException("SQL 不可用"))
                .when(rankCache).rebuild(any());

        org.assertj.core.api.Assertions.assertThatCode(() -> loader.load())
                .doesNotThrowAnyException();
        // 冷启动 SQL 故障也是真异常,需要上报(区别于脏数据 + rebuild SUCCESS 的正常恢复)
        ArgumentCaptor<RuntimeException> captor = ArgumentCaptor.forClass(RuntimeException.class);
        verify(publisher, times(1)).publishSystemError(captor.capture());
        assertThat(captor.getValue()).isInstanceOf(TopRankRebuildFailedException.class);
    }

    @Test
    void shouldReturnEmptyWhenRebuildStillYieldsCorruptedData() {
        // 重建后立即再读仍脏:返回空,下次用户访问时由"脏数据 → 重建"路径处理
        // 冷启动 rebuild SUCCESS 但 Redis 又被外部污染,只 log.info 不上报
        when(rankCache.readTop(anyInt()))
                .thenReturn(List.of())
                .thenThrow(new RankDataCorruptedException("1", "1000", "null"));
        when(rankCache.rebuild(any())).thenReturn(RebuildOutcome.SUCCESS);

        List<DashboardTopProductVO> result = loader.load();

        assertThat(result).isEmpty();
        // 冷启动 + rebuild SUCCESS 后再读脏数据:不重复上报(避免与凌晨 Refresher 上报重复)
        verify(publisher, never()).publishSystemError(any());
    }

    @Test
    void shouldReturnEmptyWhenReadTopReturnsEmptyAndRebuildReturnsEmpty() {
        // 空数据 + 重建也空:正常返回空
        when(rankCache.readTop(anyInt())).thenReturn(List.of());
        when(rankCache.rebuild(any())).thenReturn(RebuildOutcome.SUCCESS);

        List<DashboardTopProductVO> result = loader.load();

        assertThat(result).isEmpty();
    }

    @Test
    void shouldHandleSkippedLockBusyOutcome() {
        // 多实例并发:其他实例已拿到锁 → 当前实例 SKIPPED_LOCK_BUSY → 业务返回空,不重复上报
        when(rankCache.readTop(anyInt())).thenThrow(new RankDataCorruptedException("1", "1000", "null"));
        when(rankCache.rebuild(any())).thenReturn(RebuildOutcome.SKIPPED_LOCK_BUSY);

        List<DashboardTopProductVO> result = loader.load();

        assertThat(result).isEmpty();
        // SKIPPED 路径不重复上报系统异常(其他实例可能已上报)
        verify(publisher, never()).publishSystemError(any());
    }
}
