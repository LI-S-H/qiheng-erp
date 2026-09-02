package com.qiheng.erp.common.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Component
public class RedisUtil {

    private final StringRedisTemplate template;
    private final ObjectMapper objectMapper;

    public RedisUtil(StringRedisTemplate template, ObjectMapper objectMapper) {
        this.template = template;
        this.objectMapper = objectMapper;
    }

    // ==================== 通用 KV ====================

    public String get(String key) {
        return template.opsForValue().get(key);
    }

    public void set(String key, String value, Duration ttl) {
        template.opsForValue().set(key, value, ttl.toMillis(), TimeUnit.MILLISECONDS);
    }

    public Boolean delete(String key) {
        return template.delete(key);
    }

    public Long delete(Set<String> keys) {
        return template.delete(keys);
    }

    public Boolean hasKey(String key) {
        return template.hasKey(key);
    }

    public Boolean expire(String key, Duration ttl) {
        return template.expire(key, ttl);
    }

    // ==================== 对象存取 ====================

    /**
     * 从 Redis 中获取对象，使用 Class 反序列化。
     * @param key Redis 键
     * @param clazz 类型，用于反序列化 JSON 字符串
     * @return 反序列化后的对象
     */
    public <T> T getObject(String key, Class<T> clazz) {
        String json = template.opsForValue().get(key);
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, clazz);
        } catch (JsonProcessingException e) {
            log.warn("Redis反序列化失败, key={}", key, e);
            return null;
        }
    }

    /**
     * 从 Redis 中获取对象，使用 TypeReference 反序列化。
     * @param key Redis 键
     * @param typeRef 类型引用，用于反序列化 JSON 字符串
     * @return 反序列化后的对象
     */
    public <T> T getObject(String key, TypeReference<T> typeRef) {
        String json = template.opsForValue().get(key);
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, typeRef);
        } catch (JsonProcessingException e) {
            log.warn("Redis反序列化失败, key={}", key, e);
            return null;
        }
    }

    /**
     * 将对象序列化为 JSON 字符串，并存储到 Redis 中。
     * @param key Redis 键
     * @param value 要存储的对象
     * @param ttl 过期时间
     */
    public void setObject(String key, Object value, Duration ttl) {
        try {
            String json = objectMapper.writeValueAsString(value);
            template.opsForValue().set(key, json, ttl.toMillis(), TimeUnit.MILLISECONDS);
        } catch (JsonProcessingException e) {
            log.warn("Redis序列化失败, key={}", key, e);
        }
    }

    // ==================== Hash 存取 ====================

    /**
     * 批量读取 Hash 字段；缺失字段在返回列表中保持为 {@code null}，顺序与请求字段一致。
     */
    public List<String> hashMultiGet(String key, Collection<String> fields) {
        if (fields == null || fields.isEmpty()) {
            return List.of();
        }
        return template.opsForHash().multiGet(key, List.copyOf(fields)).stream()
                .map(value -> value == null ? null : String.valueOf(value))
                .toList();
    }

    /**
     * 批量写入 Hash 字段，调用方可将一个业务对象拆成可独立失效的字段。
     */
    public void hashPutAll(String key, Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        template.opsForHash().putAll(key, values);
    }

    /**
     * 删除 Hash 中指定字段，不会影响同一 Hash 下其他日期的数据。
     */
    public void hashDelete(String key, Collection<String> fields) {
        if (fields == null || fields.isEmpty()) {
            return;
        }
        template.opsForHash().delete(key, fields.toArray());
    }
    /** 返回 Hash 的全部字段名，供按日期窗口清理历史字段。 */
    public Set<String> hashKeys(String key) {
        return template.opsForHash().keys(key).stream()
                .map(String::valueOf)
                .collect(Collectors.toSet());
    }
    // ==================== List 存取 ====================

    /**
     * 从 Redis 中获取 List 对象，使用 TypeReference 反序列化。
     * @param key Redis 键
     * @param elementType 元素类型引用，用于反序列化 JSON 字符串
     * @return 反序列化后的 List 对象
     */
    public <T> List<T> getList(String key, Class<T> elementType) {
        String json = template.opsForValue().get(key);
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, objectMapper.getTypeFactory()
                    .constructCollectionType(List.class, elementType));
        } catch (JsonProcessingException e) {
            log.warn("Redis反序列化失败, key={}", key, e);
            return null;
        }
    }

    public <T> List<T> getList(String key, TypeReference<List<T>> typeRef) {
        String json = template.opsForValue().get(key);
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, typeRef);
        } catch (JsonProcessingException e) {
            log.warn("Redis反序列化失败, key={}", key, e);
            return null;
        }
    }

    /**
     * 将 List 对象序列化为 JSON 字符串，并存储到 Redis 中。
     * @param key Redis 键
     * @param value 要存储的 List 对象
     * @param ttl 过期时间
     */
    public <T> void setList(String key, List<T> value, Duration ttl) {
        setObject(key, value, ttl);
    }

    // ==================== 批量 key 删除 ====================

    /**
     * 根据模式删除 Redis 中的键值对。
     * @param pattern 键的模式，用于匹配要删除的键
     */
    public void deleteByPattern(String pattern) {
        Set<String> keys = template.keys(pattern);
        if (keys.isEmpty()) {
            template.delete(keys);
        }
    }
}