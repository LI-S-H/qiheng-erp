package com.qiheng.erp.ai.python.sandbox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.zerodep.ZerodepDockerHttpClient;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.redisson.Redisson;
import org.redisson.config.Config;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.boot.convert.ApplicationConversionService;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SandboxRetentionTest {
    @Test
    void springCreatesRetentionUsingInjectedRedis() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getBeanFactory().setConversionService(ApplicationConversionService.getSharedInstance());
            context.registerBean(RedissonClient.class, () -> mock(RedissonClient.class));
            context.register(SandboxRetention.class);
            context.refresh();
            assertThat(context.getBean(SandboxRetention.class)).isNotNull();
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "ERP_SANDBOX_RETENTION_DOCKER_TEST", matches = "true")
    void newProcessViewCleansExpiredRegisteredContainerFromRedis() throws Exception {
        var redisConfig = new Config();
        redisConfig.useSingleServer().setAddress("redis://127.0.0.1:16327").setDatabase(15);
        var redis = Redisson.create(redisConfig);
        var records = redis.<String, String>getMap("erp:ai:sandbox:containers", StringCodec.INSTANCE);
        org.junit.jupiter.api.Assumptions.assumeTrue(records.isEmpty(), "测试 Redis DB 15 必须没有已有沙箱记录");
        var dockerConfig = DefaultDockerClientConfig.createDefaultConfigBuilder().build();
        var http = new ZerodepDockerHttpClient.Builder().dockerHost(dockerConfig.getDockerHost())
                .sslConfig(dockerConfig.getSSLConfig()).build();
        try (var docker = DockerClientImpl.getInstance(dockerConfig, http)) {
            String id = docker.createContainerCmd("redis:7-alpine")
                    .withName("codex-retention-" + java.util.UUID.randomUUID()).exec().getId();
            try {
                docker.startContainerCmd(id).exec();
                var oldProcess = new SandboxRetention(redis, Duration.ZERO);
                oldProcess.start("crashed-task");
                oldProcess.register("crashed-task", id);
                // 新实例没有旧进程的内存任务集合；Redis 登记仍然存在。
                var newProcess = new SandboxRetention(redis, Duration.ZERO);
                newProcess.cleanup();
                assertThat(records.containsKey(id)).isFalse();
                assertThatThrownBy(() -> docker.inspectContainerCmd(id).exec()).isInstanceOf(NotFoundException.class);
            } finally {
                records.remove(id);
                try {
                    docker.removeContainerCmd(id).withForce(true).exec();
                } catch (NotFoundException ignored) {
                    // 测试完成时容器已经被清理。
                }
            }
        } finally {
            redis.shutdown();
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "ERP_SANDBOX_RETENTION_DOCKER_TEST", matches = "true")
    void deletesOnlyRegisteredTestContainer() throws Exception {
        var config = DefaultDockerClientConfig.createDefaultConfigBuilder().build();
        var http = new ZerodepDockerHttpClient.Builder().dockerHost(config.getDockerHost())
                .sslConfig(config.getSSLConfig()).build();
        try (var docker = DockerClientImpl.getInstance(config, http)) {
            String id = docker.createContainerCmd("redis:7-alpine")
                    .withName("codex-retention-" + java.util.UUID.randomUUID()).exec().getId();
            try {
                docker.startContainerCmd(id).exec();
                var redis = mock(RedissonClient.class);
                @SuppressWarnings("unchecked")
                RMap<String, String> records = mock(RMap.class);
                Map<String, String> stored = new HashMap<>();
                when(redis.<String, String>getMap(anyString(), eq(StringCodec.INSTANCE))).thenReturn(records);
                when(records.readAllMap()).thenAnswer(call -> new HashMap<>(stored));
                when(records.put(anyString(), anyString())).thenAnswer(call -> stored.put(call.getArgument(0), call.getArgument(1)));
                when(records.remove(anyString(), anyString())).thenAnswer(call -> stored.remove(call.getArgument(0), call.getArgument(1)));
                var retention = new SandboxRetention(redis, Duration.ZERO);
                retention.start("test-task");
                retention.register("test-task", id);
                retention.cleanup();
                assertThat(docker.inspectContainerCmd(id).exec()).isNotNull();
                retention.finish("test-task");
                assertThat(stored).isEmpty();
                assertThatThrownBy(() -> docker.inspectContainerCmd(id).exec()).isInstanceOf(NotFoundException.class);
            } finally {
                try {
                    docker.removeContainerCmd(id).withForce(true).exec();
                } catch (NotFoundException ignored) {
                    // 测试正常完成时容器已经被清理。
                }
            }
        }
    }

    @Test
    void keepsActiveAndRetriesFailedDeletionForEachContainer() {
        var redis = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RMap<String, String> records = mock(RMap.class);
        Map<String, String> stored = new HashMap<>();
        when(redis.<String, String>getMap(anyString(), eq(StringCodec.INSTANCE))).thenReturn(records);
        when(records.readAllMap()).thenAnswer(call -> new HashMap<>(stored));
        when(records.put(anyString(), anyString())).thenAnswer(call -> stored.put(call.getArgument(0), call.getArgument(1)));
        when(records.remove(anyString(), anyString())).thenAnswer(call -> stored.remove(call.getArgument(0), call.getArgument(1)));
        var attempts = new AtomicInteger();
        var retention = new SandboxRetention(redis, Duration.ZERO, id -> attempts.incrementAndGet() > 1);
        String first = "a".repeat(64);
        String second = "b".repeat(64);
        retention.start("task");
        retention.register("task", first);
        retention.register("task", second);
        retention.touch("task");
        retention.cleanup();
        assertThat(attempts).hasValue(0);
        assertThat(stored).hasSize(2);
        retention.finish("task");
        assertThat(stored).hasSize(1);
        retention.cleanup();
        assertThat(stored).isEmpty();
        assertThat(attempts).hasValue(3);
    }
}
