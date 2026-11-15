package com.qiheng.erp.ai.python.sandbox;

import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.model.HostConfig;
import io.agentscope.runtime.sandbox.manager.client.container.docker.DockerClientStarter;
import io.agentscope.runtime.sandbox.manager.model.fs.VolumeBinding;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class IsolatedDockerClientStarterTest {
    @Test
    void createsContainerOnSandboxNetworkWithoutPublishedPorts() {
        var docker = mock(com.github.dockerjava.api.DockerClient.class);
        var command = mock(CreateContainerCmd.class, RETURNS_SELF);
        var response = mock(CreateContainerResponse.class);
        when(docker.createContainerCmd(anyString())).thenReturn(command);
        when(command.exec()).thenReturn(response);
        var client = new IsolatedDockerClientStarter.IsolatedDockerClient(
                DockerClientStarter.builder().build(), null, "qiheng-erp-sandbox");

        assertSame(response, client.createContainers(docker, "agentbay_test_123", "sandbox:local",
                List.of("8000/tcp"), Map.of("8000/tcp", 51000),
                List.of(new VolumeBinding("sessions_mount_dir/test", "/workspace", "rw")), Map.of(),
                Map.of("mem_limit", "512m", "nano_cpus", 1_000_000_000L, "privileged", false)));

        var hostConfigs = ArgumentCaptor.forClass(HostConfig.class);
        verify(command, atLeastOnce()).withHostConfig(hostConfigs.capture());
        var config = hostConfigs.getValue();
        assertEquals("qiheng-erp-sandbox", config.getNetworkMode());
        assertTrue(config.getPortBindings() == null || config.getPortBindings().getBindings().isEmpty());
        assertTrue(config.getBinds() == null || config.getBinds().length == 0);
        assertEquals(536_870_912L, config.getMemory());
        assertEquals(1_000_000_000L, config.getNanoCPUs());
        assertFalse(Boolean.TRUE.equals(config.getPrivileged()));
        var aliases = ArgumentCaptor.forClass(String[].class);
        verify(command).withAliases(aliases.capture());
        assertNotNull(java.net.URI.create("http://" + aliases.getValue()[0] + ":8000").getHost());
    }

    @Test
    void rejectsHostMountsAndInvalidNetworkNames() {
        var docker = mock(com.github.dockerjava.api.DockerClient.class);
        var client = new IsolatedDockerClientStarter.IsolatedDockerClient(
                DockerClientStarter.builder().build(), null, "qiheng-erp-sandbox");
        assertThrows(IllegalArgumentException.class, () -> client.createContainers(docker, "sandbox-test",
                "sandbox:local", List.of("8000/tcp"), Map.of(), List.of(mock(VolumeBinding.class)), Map.of(), Map.of()));
        verifyNoInteractions(docker);
        assertThrows(IllegalArgumentException.class, () -> new IsolatedDockerClientStarter("bad/network"));
    }
}
