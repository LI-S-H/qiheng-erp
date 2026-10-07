package com.qiheng.erp.purchase.service.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiheng.erp.common.constant.SupplierScoreRedisKeys;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.Collections;

/**
 * pending key 比较删除工具。
 *
 * <p>Consumer 在供应商锁内先读取批次，评分事务成功后再按 batchNo 比较删除。
 * 重算失败时 pending 保留，MQ 重投可以继续执行；旧消息也不会删除新窗口。</p>
 *
 * @author Li
 * @since 2026-09-23
 */
@Component
@RequiredArgsConstructor
public class PendingRedisSupport {

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    private DefaultRedisScript<Long> compareDeleteScript;

    /** 初始化原子比较删除脚本，防止旧消息清理新批次的 pending。 */
    @PostConstruct
    public void init() {
        compareDeleteScript = new DefaultRedisScript<>();
        compareDeleteScript.setResultType(Long.class);
        // 脚本直接内嵌，避免依赖跨模块资源打包；比较与删除仍由 Redis 原子执行。
        compareDeleteScript.setScriptText("""
                local pending = redis.call('GET', KEYS[1])
                if pending and cjson.decode(pending).batchNo == ARGV[1] then
                    redis.call('DEL', KEYS[1])
                    return 1
                else
                    return 0
                end
                """);
    }

    /**
     * 比较并删除 pending key。
     *
     * @param supplierId 目标供应商 ID
     * @param batchNo 当前 SCHEDULED_FIRE 消息携带的评分批次号
     * @return true=成功清理当前批次;false=批次已不存在或被新窗口接管
     */
    public boolean compareAndDelete(Long supplierId, String batchNo) {
        if (supplierId == null || batchNo == null) {
            return false;
        }
        String key = SupplierScoreRedisKeys.pendingKey(supplierId);
        Long result = stringRedisTemplate.execute(compareDeleteScript,
                Collections.singletonList(key), batchNo);
        if (result == null) {
            throw new IllegalStateException("pending 清理未得到 Redis 执行结果 supplierId=" + supplierId);
        }
        return result == 1L;
    }

    /**
     * 在供应商锁内读取并核对当前批次；Redis 或 JSON 故障抛出异常交给 MQ 重试。
     *
     * @param supplierId 目标供应商 ID
     * @param batchNo 当前消息携带的评分批次号，同时标识合并窗口
     * @return 匹配的窗口快照；pending 不存在或批次不匹配时返回 null
     */
    public ScoreRecalcPendingService.PendingSnapshot findMatchingSnapshot(Long supplierId, String batchNo) {
        String json = stringRedisTemplate.opsForValue().get(SupplierScoreRedisKeys.pendingKey(supplierId));
        if (json == null) {
            return null;
        }
        try {
            ScoreRecalcPendingService.PendingSnapshot snapshot = objectMapper.readValue(
                    json, ScoreRecalcPendingService.PendingSnapshot.class);
            return batchNo.equals(snapshot.getBatchNo()) ? snapshot : null;
        } catch (Exception e) {
            throw new IllegalStateException("pending 快照解析失败 supplierId=" + supplierId, e);
        }
    }
}
