package com.qiheng.erp.ai.python.sandbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.runtime.sandbox.box.BaseSandbox;
import io.agentscope.runtime.sandbox.manager.ManagerConfig;
import io.agentscope.runtime.sandbox.manager.SandboxService;
import io.agentscope.runtime.sandbox.manager.client.container.docker.DockerClientStarter;
import io.agentscope.runtime.sandbox.manager.model.sandbox.SandboxConfig;
import io.agentscope.runtime.sandbox.manager.registry.SandboxRegistryService;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 按需启动沙箱管理器，并为每个分析任务创建独立沙箱。
 *
 * @author Li
 * @since 2026-11-10
 */
@Component
public final class PythonSandboxRuntime implements AutoCloseable {
    private final ObjectMapper mapper;
    private final String image;
    private final Set<PythonSandboxSession> sessions = new HashSet<>();
    private SandboxService service;
    private boolean closed;

    /** 配置任务沙箱使用的镜像及 JSON 序列化器。 */
    public PythonSandboxRuntime(ObjectMapper mapper,
            @Value("${erp.ai.python.sandbox-image:erp-ai-sandbox:local}") String image) {
        this.mapper = mapper;
        this.image = image;
    }

    /** 打开任务会话；容器由框架在首次执行时创建。 */
    public synchronized PythonSandboxSession openSession() {
        if (closed) {
            throw new IllegalStateException("Python 沙箱管理器已关闭");
        }
        if (service == null) {
            // 首个任务初始化管理服务；之后的任务复用服务，但分别创建沙箱。
            var config = new SandboxConfig.Builder()
                    .imageName(image)
                    .sandboxType("base")
                    .securityLevel("high")
                    .timeout(120)
                    .description("ERP Python 分析沙箱")
                    .runtimeConfig(Map.of(
                            "mem_limit", "512m",
                            "nano_cpus", 1_000_000_000L,
                            "privileged", false
                    ))
                    .build();
            SandboxRegistryService.register(BaseSandbox.class, config);
            var manager = ManagerConfig.builder()
                    .containerPrefixKey("erp-ai-python")
                    .clientStarter(DockerClientStarter.builder().build())
                    .build();
            var started = new SandboxService(manager);
            started.start();
            service = started;
        }
        var sandbox = new BaseSandbox(service, "erp-ai-python", UUID.randomUUID().toString());
        var session = new PythonSandboxSession(sandbox, new PythonExecutionProtocol(mapper), mapper, this::forget);
        sessions.add(session);
        return session;
    }

    /** 会话结束后移除引用，避免管理器持有已释放的任务。 */
    private synchronized void forget(PythonSandboxSession session) {
        sessions.remove(session);
    }

    /** 只关闭本实例创建的任务，不调用跨实例的全局清理。 */
    @Override
    @PreDestroy
    public void close() {
        Set<PythonSandboxSession> toClose;
        SandboxService toStop;
        synchronized (this) {
            closed = true;
            toClose = Set.copyOf(sessions);
            toStop = service;
            service = null;
        }
        for (PythonSandboxSession session : toClose) {
            session.close();
        }
        if (toStop != null) {
            toStop.close();
        }
    }
}
