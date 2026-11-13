package com.qiheng.erp.ai.python.sandbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** 独立 JVM 强制退出后，观察其真实沙箱容器是否残留。 */
@EnabledIfEnvironmentVariable(named = "ERP_PYTHON_SANDBOX_IT", matches = "true")
class PythonSandboxCrashIT {
    /** 子 JVM 就绪后强制终止，仅检查和清理该 JVM 返回的容器 ID。 */
    @Test
    void observesContainerAfterProcessCrash() throws Exception {
        String executable = Path.of(System.getProperty("java.home"), "bin", "java.exe").toString();
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        Path arguments = Files.createTempFile("python-crash-probe-", ".args");
        Files.writeString(arguments, "--class-path\n\"" + classpath.replace('\\', '/') + "\"\n" + Probe.class.getName());
        var process = new ProcessBuilder(executable, "@" + arguments)
                .redirectErrorStream(true).start();
        String containerId = null;
        try {
            var reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
            var ready = java.util.concurrent.Executors.newSingleThreadExecutor();
            try {
                var future = ready.submit(() -> {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.startsWith("CRASH_PROBE_ID=")) {
                            return line.substring("CRASH_PROBE_ID=".length()).trim();
                        }
                    }
                    throw new IllegalStateException("测试子进程未报告容器 ID");
                });
                containerId = future.get(120, TimeUnit.SECONDS);
            } finally {
                ready.shutdownNow();
            }
            assertTrue(containerId.matches("[0-9a-f]{12}"), "容器 ID 格式无效");
            process.destroyForcibly();
            assertTrue(process.waitFor(20, TimeUnit.SECONDS));
            var inspect = new ProcessBuilder("docker", "inspect", "-f", "{{.State.Running}}", containerId)
                    .redirectErrorStream(true).start();
            String state = new String(inspect.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            assertTrue(inspect.waitFor(20, TimeUnit.SECONDS));
            System.out.println("CRASH_PROBE_RESULT exists=" + (inspect.exitValue() == 0) + " state=" + state);
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(20, TimeUnit.SECONDS);
            }
            if (containerId != null && containerId.matches("[0-9a-f]{12}")) {
                var cleanup = new ProcessBuilder("docker", "rm", "-f", containerId).redirectErrorStream(true).start();
                cleanup.getInputStream().readAllBytes();
                cleanup.waitFor(30, TimeUnit.SECONDS);
            }
            Files.deleteIfExists(arguments);
        }
    }

    /** 不关闭 runtime，等待父进程模拟 JVM 意外终止。 */
    public static final class Probe {
        public static void main(String[] args) throws Exception {
            ((ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME))
                    .setLevel(ch.qos.logback.classic.Level.WARN);
            var runtime = new PythonSandboxRuntime(new ObjectMapper(), "erp-ai-sandbox-probe:local");
            try {
                var result = runtime.openSession().execute("import socket; print(socket.gethostname())", 30, 1000);
                if (!result.success()) {
                    throw new IllegalStateException("测试沙箱未能执行");
                }
                System.out.println("CRASH_PROBE_ID=" + result.stdout().trim());
                System.out.flush();
                Thread.sleep(TimeUnit.MINUTES.toMillis(5));
            } finally {
                // 正常报错时释放；强制终止 JVM 则不会执行此处。
                runtime.close();
            }
        }
    }
}
