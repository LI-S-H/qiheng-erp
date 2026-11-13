package com.qiheng.erp.ai.python.sandbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.agentscope.runtime.sandbox.box.BaseSandbox;
import io.agentscope.runtime.sandbox.manager.model.container.ContainerModel;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PythonSandboxClientTest {
    @Test
    void usesSandboxAuthenticationAndDoesNotExposeFailedResponse() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var status = new AtomicInteger(200);
        var authorization = new AtomicReference<String>();
        var session = new AtomicReference<String>();
        server.createContext("/fastapi/healthz", exchange -> {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.createContext("/fastapi/tools/run_ipython_cell", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            session.set(exchange.getRequestHeaders().getFirst("x-agentscope-runtime-session-id"));
            exchange.getRequestBody().readAllBytes();
            byte[] response = (status.get() == 200 ? "{\"isError\":false}" : "PRIVATE_RESPONSE")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status.get(), response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            var info = new ContainerModel();
            info.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/fastapi");
            info.setAuthToken("test-token");
            info.setSessionId("session-id");
            var sandbox = mock(BaseSandbox.class);
            when(sandbox.getInfo()).thenReturn(info);
            var client = new PythonSandboxClient(sandbox, new ObjectMapper());

            assertEquals("{\"isError\":false}", client.run("PRIVATE_CODE"));
            assertEquals("Bearer test-token", authorization.get());
            assertEquals("ssession-id", session.get());
            status.set(403);
            var error = assertThrows(IllegalStateException.class, () -> client.run("PRIVATE_CODE"));
            assertFalse(error.getMessage().contains("PRIVATE_RESPONSE"));
            assertFalse(error.getMessage().contains("PRIVATE_CODE"));
            assertFalse(error.getMessage().contains("test-token"));
            verify(sandbox, times(1)).getInfo();
        } finally {
            server.stop(0);
        }
    }
}
