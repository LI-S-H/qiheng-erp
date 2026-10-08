package com.qiheng.erp.dashboard.loader;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qiheng.erp.common.mq.SystemExceptionMqPublisher;
import com.qiheng.erp.common.util.QtyUtil;
import com.qiheng.erp.dashboard.cache.RankDataCorruptedException;
import com.qiheng.erp.dashboard.cache.TopProductRankCache;
import com.qiheng.erp.dashboard.cache.TopProductRankCache.RankEntry;
import com.qiheng.erp.dashboard.cache.TopProductRankCache.RebuildOutcome;
import com.qiheng.erp.dashboard.cache.TopProductRankRefresher;
import com.qiheng.erp.dashboard.cache.TopRankRebuildFailedException;
import com.qiheng.erp.dashboard.domain.topproduct.vo.DashboardTopProductVO;
import com.qiheng.erp.product.domain.entity.Product;
import com.qiheng.erp.product.mapper.ProductMapper;
import com.qiheng.erp.warehouse.domain.warehousestock.entity.WarehouseStock;
import com.qiheng.erp.warehouse.mapper.WarehouseStockMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 工作台销售商品排行聚合器。
 *
 * <p>排行数据（近 30 天累计金额与数量）由 {@link TopProductRankCache} 维护，
 * 本类负责从 Redis 取前 N 个商品后实时补齐商品资料（编码、名称、可用库存）。</p>
 *
 * <p>脏数据防护：readTop 检测到不可解析的条目时抛 {@link RankDataCorruptedException}，
 * 本类捕获后上报系统异常 + 直接调用 rebuild()。
 * <b>并发去重由 rebuild() 内部的 Redisson tryLock 天然完成</b>——多实例并发触发时,
 * 只有一个实例能拿到 REBUILD_LOCK_KEY 跑 supplier + 写 Redis;其他实例 SKIPPED_LOCK_BUSY
 * 立即返回空(用户刷新即可)。失败时由 {@link TopProductRankRefresher} 兜底上报。</p>
 *
 * <p>职责边界：
 * <ul>
 *   <li>{@link TopProductRankCache} 只负责"读 + 抛异常 + 内部 Redisson 锁 rebuild",不触发任何外部写动作</li>
 *   <li>{@link TopProductRankRefresher} 负责"凌晨定时触发 rebuild + 上报失败"</li>
 *   <li>本类负责"脏数据异常捕获 + 上报 + 触发 rebuild + VO 组装"</li>
 * </ul>
 * </p>
 *
 * @author Li
 * @since 2026-09-02
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DashboardTopProductLoader {

    /** 工作台首页展示的商品数上限 */
    private static final int TOP_LIMIT = 10;

    /**
     * 从 Redis 多取一倍的冗余系数：
     * 应对凌晨后被新删除的商品淘汰，渲染前过滤已删除，凑齐有效数即停。
     * 不取太多避免无意义的 Redis 网络/反序列化开销。
     */
    private static final int FETCH_FACTOR = 2;

    private final TopProductRankCache rankCache;
    private final TopProductRankRefresher refresher;
    private final ProductMapper productMapper;
    private final WarehouseStockMapper warehouseStockMapper;
    private final SystemExceptionMqPublisher systemExceptionMqPublisher;

    /**
     * 加载销售商品排行。
     *
     * <p>三段式处理：
     * <ul>
     *   <li>脏数据（{@link RankDataCorruptedException}）：上报 + 触发 rebuild + 返回空
     *       （并发由 Redisson 锁去重,其他实例 SKIPPED_LOCK_BUSY 立即返回空）</li>
     *   <li>冷启动（readTop 空）：触发 rebuild + 再读（重建失败时也返回空）</li>
     *   <li>正常：组装 VO，已删除商品跳过凑齐 TOP_LIMIT 个</li>
     * </ul>
     *
     * <p>用户视角:脏数据 / 冷启动返回空时刷新页面即可。
     * rebuild SUCCESS 之后下一次访问即正常返回数据;FAILED 仍返回空(等运维介入或凌晨重建)。</p>
     *
     * @return 销售金额降序的产品列表；空数据返回空列表而非 null
     */
    public List<DashboardTopProductVO> load() {
        // 1. 读 Redis：脏数据异常向上抛,被 try-catch 捕获后触发 rebuild
        // 脏数据本身不是错误(可能外部命令临时污染),只有 rebuild 失败才视为真异常
        // ——所以本分支不直接 publishSystemError,统一由 triggerRebuild 内部根据 outcome 决定
        List<RankEntry> top;
        try {
            top = rankCache.readTop(TOP_LIMIT * FETCH_FACTOR);
        } catch (RankDataCorruptedException ex) {
            // 脏数据路径:log.info 记录"检测到污染"(不视为错误),触发 rebuild
            log.info("TOP 排行 Redis 数据异常，触发重建");
            triggerRebuild();
            return List.of();
        }

        // 2. 冷启动:readTop 空,触发 rebuild
        if (top.isEmpty()) {
            triggerRebuild();
            try {
                top = rankCache.readTop(TOP_LIMIT * FETCH_FACTOR);
            } catch (RankDataCorruptedException ex) {
                // 重建后立刻再读仍脏——可能是同一次请求内连续重建期间外部再次污染。
                // 冷启动场景下不重复上报系统异常(避免与 Refresher 同时上报造成重复告警);
                // 下次用户访问会重新走"脏数据 → 重建"路径(由本次 rebuild 结果决定)
                log.info("TOP 排行重建后再次读到脏数据，保持空数据");
                return List.of();
            }
        }
        if (top.isEmpty()) {
            return List.of();
        }
        // 3. 收集商品 ID（readTop 已一次性带回金额+数量，无需再读 quantities）
        List<Long> productIds = new ArrayList<>(top.size());
        for (RankEntry entry : top) {
            productIds.add(entry.productId());
        }
        // 4. 实时查商品资料（已过滤逻辑删除）与可用库存（按 productId 聚合）
        Map<Long, Product> productById = loadProducts(productIds);
        Map<Long, Long> availableByProductId = loadAvailableQty(productIds);
        // 5. 组装 VO：productById 里缺失 = 已删除（凌晨后被新删）；停用商品 status=0 保留展示
        List<DashboardTopProductVO> result = new ArrayList<>(TOP_LIMIT);
        for (RankEntry entry : top) {
            if (result.size() >= TOP_LIMIT) {
                break;
            }
            Product product = productById.get(entry.productId());
            if (product == null) {
                // 已删除商品跳过；后续条目顶上补齐
                continue;
            }
            DashboardTopProductVO vo = new DashboardTopProductVO();
            vo.setProductId(entry.productId());
            vo.setProductCode(product.getProductCode());
            vo.setProductName(product.getProductName());
            vo.setSalesAmount(QtyUtil.toDecimal(entry.amount()));
            vo.setSalesQty(QtyUtil.toDecimal(entry.quantity()));
            vo.setAvailableQty(QtyUtil.toDecimal(availableByProductId.getOrDefault(entry.productId(), 0L)));
            result.add(vo);
        }
        return result;
    }

    /**
     * 触发重建(由 {@link TopProductRankCache#rebuild} 内部的 Redisson 锁去重):
     * <ul>
     *   <li>SUCCESS:当前实例拿到锁并完成重建,日志 INFO 记录(正常恢复路径,不视为错误)</li>
     *   <li>SKIPPED_LOCK_BUSY:其他实例正在重建,本实例立即返回(用户刷新即可,不视为错误)</li>
     *   <li>FAILED:锁等待中断等场景,log.warn + publishSystemError(真异常,需运维介入)</li>
     *   <li>RuntimeException 穿透:SQL/Redis 环境级故障,log.warn + publishSystemError</li>
     * </ul>
     * 重建失败由 {@link TopProductRankRefresher#refresh} 也会兜底上报 HIGH(本方法与之并存,场景不重叠:
     * Refresher 是凌晨定时调度,本方法是用户访问触发的脏数据/冷启动场景)。
     */
    private void triggerRebuild() {
        try {
            RebuildOutcome outcome = rankCache.rebuild(refresher::queryRankEntries);
            switch (outcome) {
                case SUCCESS -> log.info("TOP 排行重建成功，数据已修复");
                case SKIPPED_LOCK_BUSY -> log.info("TOP 排行重建被其他实例抢占，用户刷新即可");
                case FAILED -> {
                    // 锁等待中断:真异常(Redisson 锁状态异常),需运维介入
                    log.warn("TOP 排行重建失败(锁等待中断)，上报系统异常");
                    systemExceptionMqPublisher.publishSystemError(
                            new TopRankRebuildFailedException("锁等待中断", null));
                }
            }
        } catch (Exception ex) {
            // SQL/Redis 环境级故障穿透 rebuild 抛出:真异常,需运维介入
            log.warn("TOP 排行重建抛异常，上报系统异常", ex);
            systemExceptionMqPublisher.publishSystemError(
                    new TopRankRebuildFailedException("SQL/Redis 故障", ex));
        }
    }

    /**
     * 按 productId 批量查未删除商品。
     *
     * <p>LambdaQueryWrapper 在 Spring 容器外构造会触发 lambda cache 异常,
     * 单测里用 Mock 拦截此处调用即可绕过。</p>
     *
     * @return 已删除的商品不会出现在结果中,渲染时通过 productById.get(id) == null 自然跳过
     */
    private Map<Long, Product> loadProducts(List<Long> productIds) {
        List<Product> products = productMapper.selectList(
                new LambdaQueryWrapper<Product>()
                        .select(Product::getId, Product::getProductCode, Product::getProductName)
                        .in(Product::getId, productIds));
        Map<Long, Product> map = new HashMap<>(products.size() * 2);
        for (Product product : products) {
            map.put(product.getId(), product);
        }
        return map;
    }

    /** 按 productId 聚合可用库存（available = stockQty - lockedQty，跨仓库求和） */
    private Map<Long, Long> loadAvailableQty(List<Long> productIds) {
        List<WarehouseStock> stocks = warehouseStockMapper.selectList(
                new LambdaQueryWrapper<WarehouseStock>()
                        .select(WarehouseStock::getProductId, WarehouseStock::getStockQty, WarehouseStock::getLockedQty)
                        .in(WarehouseStock::getProductId, productIds));
        Map<Long, Long> map = new HashMap<>(stocks.size() * 2);
        for (WarehouseStock stock : stocks) {
            long available = nullSafe(stock.getStockQty()) - nullSafe(stock.getLockedQty());
            map.merge(stock.getProductId(), available, Long::sum);
        }
        return map;
    }

    /** 安全转换 Long 为 long，避免 NPE */
    private static long nullSafe(Long value) {
        return value == null ? 0L : value;
    }
}
