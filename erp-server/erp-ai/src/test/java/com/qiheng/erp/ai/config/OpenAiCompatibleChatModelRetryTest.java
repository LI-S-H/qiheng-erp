package com.qiheng.erp.ai.config;

import org.junit.jupiter.api.Test;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.retry.support.RetryTemplate;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 验证模型客户端的重试策略：429 和瞬态错误重试三次，普通 4xx 不重试。
 *
 * <p>直接读取生产装配出来的 RetryTemplate，避免测试复制一份配置造成偏差。</p>
 *
 * @author Li
 * @since 2026-11-22
 */
class OpenAiCompatibleChatModelRetryTest {

    /** 取生产代码装配的重试模板。 */
    private RetryTemplate retryTemplate() throws Exception {
        OpenAiChatModel model = OpenAiCompatibleChatModel.create(
                "test-key", "http://localhost:1", "test-model", 0.0, Duration.ofSeconds(5), null);
        Field field = OpenAiChatModel.class.getDeclaredField("retryTemplate");
        field.setAccessible(true);
        return (RetryTemplate) field.get(model);
    }

    /** 429 限流属于可重试错误，应尝试三次后放弃。 */
    @Test
    void retriesThrottlingThreeTimes() throws Exception {
        RetryTemplate template = retryTemplate();
        AtomicInteger attempts = new AtomicInteger();
        assertThrows(NonTransientAiException.class, () -> template.execute(context -> {
            attempts.incrementAndGet();
            throw new NonTransientAiException("429 - Too many requests");
        }));
        assertEquals(3, attempts.get());
    }

    /** 参数类 4xx 重试没有意义，只尝试一次。 */
    @Test
    void doesNotRetryPlainClientError() throws Exception {
        RetryTemplate template = retryTemplate();
        AtomicInteger attempts = new AtomicInteger();
        assertThrows(NonTransientAiException.class, () -> template.execute(context -> {
            attempts.incrementAndGet();
            throw new NonTransientAiException("400 - bad request");
        }));
        assertEquals(1, attempts.get());
    }

    /** 网络或服务端瞬态错误同样重试三次。 */
    @Test
    void retriesTransientError() throws Exception {
        RetryTemplate template = retryTemplate();
        AtomicInteger attempts = new AtomicInteger();
        assertThrows(TransientAiException.class, () -> template.execute(context -> {
            attempts.incrementAndGet();
            throw new TransientAiException("503 service unavailable");
        }));
        assertEquals(3, attempts.get());
    }
}