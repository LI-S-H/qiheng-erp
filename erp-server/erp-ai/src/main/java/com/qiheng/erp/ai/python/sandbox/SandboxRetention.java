package com.qiheng.erp.ai.python.sandbox;

import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.zerodep.ZerodepDockerHttpClient;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;

/** 单实例分析沙箱登记；活动任务受进程内集合保护，遗留容器在过期后重试清理。 */
@Component
public final class SandboxRetention {
    private static final Logger log = LoggerFactory.getLogger(SandboxRetention.class);
    private static final String KEY = "erp:ai:sandbox:containers";
    private final RedissonClient redis;
    private final Duration idle;
    private final Set<String> active = new HashSet<>();
    private final Predicate<String> remover;

    @Autowired
    public SandboxRetention(RedissonClient redis,
            @Value("${erp.ai.python.sandbox-idle-timeout:PT1H}") Duration idle) {
        this.redis = redis;
        this.idle = idle;
        this.remover = this::deleteContainer;
    }

    SandboxRetention(RedissonClient redis, Duration idle, Predicate<String> remover) {
        this.redis = redis;
        this.idle = idle;
        this.remover = remover;
    }

    /** 在容器创建之前保护本次任务，避免扫描与启动竞争。 */
    public synchronized void start(String taskId) {
        active.add(taskId);
    }

    /** 模型输出一轮过程时刷新本任务所有容器的活动时间。 */
    public synchronized void touch(String taskId) {
        if (!active.contains(taskId)) {
            return;
        }
        long now = System.currentTimeMillis();
        var records = redis.<String, String>getMap(KEY, StringCodec.INSTANCE);
        for (var entry : records.readAllMap().entrySet()) {
            if (entry.getValue().startsWith(taskId + "|")) {
                records.put(entry.getKey(), taskId + "|" + now);
            }
        }
    }

    /** 每个实际容器单独登记，重建时不会覆盖旧容器。 */
    public synchronized void register(String taskId, String containerId) {
        if (containerId == null || !containerId.matches("[0-9a-f]{64}")) {
            throw new IllegalStateException("无法确认沙箱容器 ID");
        }
        long now = System.currentTimeMillis();
        redis.<String, String>getMap(KEY, StringCodec.INSTANCE).put(containerId, taskId + "|" + now);
    }

    /** 正常任务结束后解除进程内保护，记录由清理任务确认删除。 */
    public synchronized void finish(String taskId) {
        active.remove(taskId);
        var records = redis.<String, String>getMap(KEY, StringCodec.INSTANCE);
        for (var entry : records.readAllMap().entrySet()) {
            if (!entry.getValue().startsWith(taskId + "|")) {
                continue;
            }
            try {
                if (remover.test(entry.getKey())) {
                    records.remove(entry.getKey(), entry.getValue());
                }
            } catch (RuntimeException error) {
                log.warn("沙箱关闭确认失败，保留登记供后续重试 containerId={}", entry.getKey(), error);
            }
        }
    }

    /** 每五分钟处理过期登记；通信失败时保留记录供下次重试。 */
    @Scheduled(fixedDelayString = "${erp.ai.python.sandbox-cleanup-interval-ms:300000}")
    public synchronized void cleanup() {
        var records = redis.<String, String>getMap(KEY, StringCodec.INSTANCE);
        for (var entry : records.readAllMap().entrySet()) {
            String[] parts = entry.getValue().split("\\|", 2);
            try {
                if (parts.length != 2 || active.contains(parts[0])
                        || System.currentTimeMillis() - Long.parseLong(parts[1]) < idle.toMillis()) {
                    continue;
                }
                if (remover.test(entry.getKey())) {
                    records.remove(entry.getKey(), entry.getValue());
                }
            } catch (RuntimeException error) {
                log.warn("沙箱清理失败，下次继续重试 containerId={}", entry.getKey(), error);
            }
        }
    }

    /** 直接调用 Docker，只有删除成功或明确不存在才视为已清理。 */
    private boolean deleteContainer(String containerId) {
        var config = DefaultDockerClientConfig.createDefaultConfigBuilder().build();
        var http = new ZerodepDockerHttpClient.Builder().dockerHost(config.getDockerHost())
                .sslConfig(config.getSSLConfig()).build();
        try (var client = DockerClientImpl.getInstance(config, http)) {
            client.removeContainerCmd(containerId).withForce(true).withRemoveVolumes(true).exec();
            return true;
        } catch (NotFoundException error) {
            return true;
        } catch (IOException error) {
            throw new IllegalStateException("Docker 连接关闭失败", error);
        }
    }
}
