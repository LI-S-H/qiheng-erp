package com.qiheng.erp.ai.python.sandbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import io.agentscope.runtime.sandbox.box.BaseSandbox;
import io.agentscope.runtime.sandbox.manager.model.container.ContainerModel;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 调用任务沙箱内的 Python 工具，不记录代码或数据请求正文。
 *
 * @author Li
 * @since 2026-11-10
 */
final class PythonSandboxClient {
    private final BaseSandbox sandbox;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private ContainerModel connection;
    private final Consumer<String> onCreated;

    /** 绑定单个任务沙箱，并准备 HTTP 请求所需的序列化器。 */
    PythonSandboxClient(BaseSandbox sandbox, ObjectMapper mapper) {
        this(sandbox, mapper, id -> {});
    }

    PythonSandboxClient(BaseSandbox sandbox, ObjectMapper mapper, Consumer<String> onCreated) {
        this.sandbox = sandbox;
        this.mapper = mapper;
        this.onCreated = onCreated;
    }

    /** 初始化容器后调用 Python 入口，错误信息不包含请求正文或认证信息。 */
    String run(String code) {
        try {
            if (connection == null) {
                connection = sandbox.getInfo();
                try {
                    onCreated.accept(connection.getContainerId());
                    awaitHealthy();
                } catch (RuntimeException | InterruptedException error) {
                    connection = null;
                    throw error;
                }
            }
            String body = mapper.writeValueAsString(Map.of("code", code));
            var request = request("/tools/run_ipython_cell")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("沙箱工具调用失败，HTTP 状态：" + response.statusCode());
            }
            var result = mapper.readTree(response.body());
            if (result == null || result.path("isError").asBoolean(false)) {
                throw new IllegalStateException("沙箱工具返回错误");
            }
            return response.body();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("沙箱调用被中断", error);
        } catch (JsonProcessingException error) {
            // 解析异常可能包含响应原文，不把数据写入异常日志。
            throw new IllegalStateException("沙箱响应格式错误");
        } catch (IOException error) {
            throw new IllegalStateException("沙箱连接失败", error);
        }
    }

    /** 容器启动后等待内部服务就绪；只在任务首次执行时调用。 */
    private void awaitHealthy() throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(120).toNanos();
        while (System.nanoTime() < deadline) {
            try {
                var response = http.send(request("/healthz").GET().build(), HttpResponse.BodyHandlers.discarding());
                if (response.statusCode() == 200) {
                    return;
                }
            } catch (IOException ignored) {
                // 沙箱服务启动期间端口可能尚未就绪，稍后重试。
            }
            Thread.sleep(250);
        }
        throw new IllegalStateException("沙箱服务启动超时");
    }

    /** 为当前沙箱构造带认证和会话标识的请求。 */
    private HttpRequest.Builder request(String path) {
        String session = "s" + connection.getSessionId();
        return HttpRequest.newBuilder(URI.create(connection.getBaseUrl() + path))
                .timeout(Duration.ofSeconds(120))
                .header("Authorization", "Bearer " + connection.getAuthToken())
                .header("Content-Type", "application/json")
                .header("x-agentrun-session-id", session)
                .header("x-agentscope-runtime-session-id", session);
    }
}
