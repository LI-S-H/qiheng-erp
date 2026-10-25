package com.qiheng.erp.ai.service.data.schema;

import com.qiheng.erp.ai.domain.model.AuthorizedSchemaSnapshot;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 授权 Schema 快照缓存。
 *
 * <p>组装一次要查三张登记表并渲染出几 KB 到二十 KB 的文本，同一个用户多轮任务反复组装
 * 就是纯浪费。缓存键是「可见范围」而不是用户或权限码——可见范围只取决于模块，
 * 同一模块下的不同权限码看到的是同一批表，用模块做键条目更少、命中率更高。</p>
 *
 * <p>缓存的是快照而不是文本：文本只是快照的一部分，两者必须同源，
 * 分开缓存会出现「模型看到的表」与「守卫放行的表」不是同一批。</p>
 *
 * <p>用进程内缓存而不是 Redis：登记数据只有几百行，进程内没有序列化开销；
 * 多实例部署时各自缓存各自的也没问题——元数据是同一份。真要跨实例共享再换实现不迟。</p>
 *
 * @author Li
 * @since 2026-10-21
 */
@Component
public class SchemaCache {

    /** 缓存条目上限。ERP 模块组合数量有限，TTL 与 get 时的惰性删除已能控制内存，上限仅作极端兜底。 */
    private static final int MAX_ENTRIES = 256;

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final long ttlMillis;

    public SchemaCache(@Value("${erp.ai.schema.cache-ttl-seconds:300}") long ttlSeconds) {
        // 0 表示立即过期，负数按 0 处理
        this.ttlMillis = Math.max(0, ttlSeconds) * 1000L;
    }

    /**
     * 取缓存的授权快照；不存在或已过期返回空。
     *
     * @param key 缓存键
     * @return 授权快照
     */
    public Optional<AuthorizedSchemaSnapshot> get(String key) {
        Entry entry = entries.get(key);
        if (entry == null) {
            return Optional.empty();
        }
        if (entry.expiresAtMillis() < System.currentTimeMillis()) {
            // 有但已过期（expiresAtMillis < 当前时间）→ 删掉这个 entry，返回 Optional.empty()
            entries.remove(key, entry);
            return Optional.empty();
        }
        return Optional.of(entry.snapshot());
    }

    /**
     * 写入缓存。
     *
     * @param key      缓存键
     * @param snapshot 授权快照
     */
    public void put(String key, AuthorizedSchemaSnapshot snapshot) {
        if (entries.size() >= MAX_ENTRIES) {
            evictExpired();
        }
        entries.put(key, new Entry(snapshot, System.currentTimeMillis() + ttlMillis));
    }

    /**
     * 清空缓存。元数据变更后必须调用，否则模型会继续看到过期结构。
     */
    public void clear() {
        entries.clear();
    }

    /**
     * 当前条目数，供观测与测试使用。
     */
    public int size() {
        return entries.size();
    }

    private void evictExpired() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Entry> entry : new LinkedHashMap<>(entries).entrySet()) {
            if (entry.getValue().expiresAtMillis() < now) {
                entries.remove(entry.getKey(), entry.getValue());
            }
        }
    }

    /**
     * 缓存条目。
     *
     * @param snapshot        授权快照
     * @param expiresAtMillis 过期时刻
     */
    private record Entry(AuthorizedSchemaSnapshot snapshot, long expiresAtMillis) {
    }
}