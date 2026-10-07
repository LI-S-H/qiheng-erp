package com.qiheng.erp.common.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RedisUtil 单元测试。
 *
 * <p>重点覆盖本轮新增的 hashEntries / hashGet / hashIncrement 三个方法。</p>
 */
@ExtendWith(MockitoExtension.class)
class RedisUtilTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private HashOperations<String, Object, Object> hashOperations;

    private RedisUtil redisUtil;

    @BeforeEach
    void setUp() {
        redisUtil = new RedisUtil(stringRedisTemplate, new ObjectMapper());
    }

    // ===== hashEntries =====

    @Test
    void hashEntriesShouldReturnTypedMap() {
        when(stringRedisTemplate.opsForHash()).thenReturn(hashOperations);
        Map<Object, Object> raw = new HashMap<>();
        raw.put("qualifiedQty", "1000");
        raw.put("fullyInboundAmount", "50000");
        when(hashOperations.entries("supplier:score:facts:1")).thenReturn(raw);

        Map<String, String> result = redisUtil.hashEntries("supplier:score:facts:1");

        assertEquals(2, result.size());
        assertEquals("1000", result.get("qualifiedQty"));
        assertEquals("50000", result.get("fullyInboundAmount"));
    }

    @Test
    void hashEntriesShouldReturnEmptyMapWhenHashIsNull() {
        when(stringRedisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.entries("missing")).thenReturn(null);

        Map<String, String> result = redisUtil.hashEntries("missing");

        assertTrue(result.isEmpty());
    }

    @Test
    void hashEntriesShouldReturnEmptyMapWhenHashIsEmpty() {
        when(stringRedisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.entries("empty")).thenReturn(new HashMap<>());

        Map<String, String> result = redisUtil.hashEntries("empty");

        assertTrue(result.isEmpty());
    }

    // ===== hashGet =====

    @Test
    void hashGetShouldReturnStringValue() {
        when(stringRedisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.get("supplier:score:facts:1", "qualifiedQty")).thenReturn("12345");

        String result = redisUtil.hashGet("supplier:score:facts:1", "qualifiedQty");

        assertEquals("12345", result);
    }

    @Test
    void hashGetShouldReturnNullWhenFieldMissing() {
        when(stringRedisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.get(anyString(), anyString())).thenReturn(null);

        String result = redisUtil.hashGet("supplier:score:facts:1", "missing");

        assertNull(result);
    }

    // ===== hashIncrement =====

    @Test
    void hashIncrementShouldReturnNewValue() {
        when(stringRedisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.increment("supplier:score:facts:1", "qualifiedQty", 10L)).thenReturn(110L);

        Long result = redisUtil.hashIncrement("supplier:score:facts:1", "qualifiedQty", 10L);

        assertEquals(110L, result);
        verify(hashOperations).increment(eq("supplier:score:facts:1"), eq("qualifiedQty"), anyLong());
    }
}