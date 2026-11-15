package com.qiheng.erp.ai.python.sandbox;

import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.HostConfig;
import io.agentscope.runtime.sandbox.manager.client.container.BaseClientStarter;
import io.agentscope.runtime.sandbox.manager.client.container.ContainerCreateResult;
import io.agentscope.runtime.sandbox.manager.client.container.docker.DockerClient;
import io.agentscope.runtime.sandbox.manager.client.container.docker.DockerClientStarter;
import io.agentscope.runtime.sandbox.manager.model.container.ContainerClientType;
import io.agentscope.runtime.sandbox.manager.model.fs.VolumeBinding;
import io.agentscope.runtime.sandbox.manager.utils.PortManager;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 让沙箱直接加入专网，避免 SDK 默认发布宿主机端口。 */
final class IsolatedDockerClientStarter extends BaseClientStarter {
    private final String network;

    IsolatedDockerClientStarter(String network) {
        super(ContainerClientType.DOCKER);
        if (network == null || !network.matches("[A-Za-z0-9][A-Za-z0-9_.-]*")) {
            throw new IllegalArgumentException("沙箱网络名称无效");
        }
        this.network = network;
    }

    @Override
    public DockerClient startClient(PortManager portManager) {
        var client = new IsolatedDockerClient(DockerClientStarter.builder().build(), portManager, network);
        if (!client.connect()) {
            throw new IllegalStateException("无法连接 Docker，无法启动 AI 沙箱");
        }
        return client;
    }

    static final class IsolatedDockerClient extends DockerClient {
        private final String network;

        IsolatedDockerClient(DockerClientStarter config, PortManager portManager, String network) {
            super(config, portManager);
            this.network = network;
        }

        @Override
        public ContainerCreateResult createContainer(String name, String image, List<String> ports,
                List<VolumeBinding> volumes, Map<String, String> environment, Map<String, Object> runtimeConfig) {
            if (ports == null || ports.isEmpty()) {
                throw new IllegalArgumentException("沙箱缺少内部服务端口");
            }
            var internalPorts = ports.stream().map(ExposedPort::parse)
                    .map(port -> Integer.toString(port.getPort())).toList();
            var result = super.createContainer(name, image, ports, volumes, environment, runtimeConfig);
            result.setIp(networkAlias(name));
            result.setPorts(internalPorts);
            return result;
        }

        @Override
        public CreateContainerResponse createContainers(com.github.dockerjava.api.DockerClient client,
                String name, String image, List<String> ports, Map<String, Integer> portMapping,
                List<VolumeBinding> volumes, Map<String, String> environment, Map<String, Object> runtimeConfig) {
            if (volumes != null && !volumes.isEmpty()
                    && (volumes.size() != 1 || !"/workspace".equals(volumes.getFirst().getContainerPath()))) {
                throw new IllegalArgumentException("专网沙箱禁止挂载宿主机目录或数据卷");
            }
            return super.createContainers(wrapClient(client, networkAlias(name)), name, image, ports, Collections.emptyMap(),
                    Collections.emptyList(), environment, runtimeConfig);
        }

        private static String networkAlias(String name) {
            return "erp-sandbox-" + UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
        }

        private com.github.dockerjava.api.DockerClient wrapClient(com.github.dockerjava.api.DockerClient client, String alias) {
            return (com.github.dockerjava.api.DockerClient) Proxy.newProxyInstance(
                    com.github.dockerjava.api.DockerClient.class.getClassLoader(),
                    new Class<?>[]{com.github.dockerjava.api.DockerClient.class}, (proxy, method, arguments) -> {
                        Object result = invoke(method, client, arguments);
                        if (method.getName().equals("createContainerCmd")) {
                            return wrapCreateCommand((CreateContainerCmd) result, alias);
                        }
                        return result;
                    });
        }

        private CreateContainerCmd wrapCreateCommand(CreateContainerCmd command, String alias) {
            return (CreateContainerCmd) Proxy.newProxyInstance(CreateContainerCmd.class.getClassLoader(),
                    new Class<?>[]{CreateContainerCmd.class}, (proxy, method, arguments) -> {
                        if (method.getName().equals("withHostConfig")) {
                            ((HostConfig) arguments[0]).withNetworkMode(network);
                        }
                        if (method.getName().equals("exec")) {
                            command.withAliases(alias);
                        }
                        Object result = invoke(method, command, arguments);
                        return result == command ? proxy : result;
                    });
        }

        private static Object invoke(java.lang.reflect.Method method, Object target, Object[] arguments) throws Throwable {
            try {
                return method.invoke(target, arguments);
            } catch (InvocationTargetException error) {
                throw error.getCause();
            }
        }
    }
}
