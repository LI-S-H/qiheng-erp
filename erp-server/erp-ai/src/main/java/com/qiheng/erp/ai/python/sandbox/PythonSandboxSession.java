package com.qiheng.erp.ai.python.sandbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.runtime.sandbox.box.BaseSandbox;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * 一个分析任务的执行会话，多次运行复用该任务的临时目录。
 *
 * @author Li
 * @since 2026-11-10
 */
public final class PythonSandboxSession implements AutoCloseable {
    public static final String OUTPUT_DIRECTORY = "/tmp/erp-analysis/output";
    public static final String INPUT_DIRECTORY = "/tmp/erp-analysis/input";
    private final BaseSandbox sandbox;
    private final PythonSandboxClient client;
    private final PythonExecutionProtocol protocol;
    private final ObjectMapper mapper;
    private final Consumer<PythonSandboxSession> onClose;
    private boolean closed;

    /** 绑定任务沙箱和管理器的会话释放回调。 */
    PythonSandboxSession(BaseSandbox sandbox, PythonExecutionProtocol protocol, ObjectMapper mapper,
                         Consumer<PythonSandboxSession> onClose) {
        this.sandbox = sandbox;
        this.protocol = protocol;
        this.mapper = mapper;
        this.onClose = onClose;
        this.client = new PythonSandboxClient(sandbox, mapper);
    }

    /** 列出任务输出文件及内容摘要，供执行前后判断文件是否变化。 */
    public synchronized List<SandboxArtifact> listArtifacts(String directory) throws IOException {
        validateArtifactDirectory(directory);
        String script = """
                import os, json, base64, hashlib
                root = '%s'
                entries = []
                os.makedirs(root, exist_ok=True)
                if os.path.islink(root):
                    raise ValueError('invalid artifact directory')
                for entry in os.scandir(root):
                    if entry.is_symlink() or not entry.is_file(follow_symlinks=False):
                        raise ValueError('output must contain regular files only')
                    size = entry.stat(follow_symlinks=False).st_size
                    if size > 16 * 1024 * 1024 or len(entries) >= 100:
                        raise ValueError('artifact limit exceeded')
                    digest = hashlib.sha256()
                    fd = os.open(entry.path, os.O_RDONLY | os.O_NOFOLLOW)
                    with os.fdopen(fd, 'rb') as source:
                        for chunk in iter(lambda: source.read(65536), b''):
                            digest.update(chunk)
                    entries.append({'name': entry.name, 'size': size, 'sha256': digest.hexdigest()})
                print('__ERP_ARTIFACTS__:' + base64.b64encode(json.dumps(entries, ensure_ascii=False).encode()).decode())
                """.formatted(directory);
        var match = Pattern.compile("__ERP_ARTIFACTS__:([A-Za-z0-9+/=]+)").matcher(runBootstrap(script));
        if (!match.find()) {
            throw new IOException("沙箱结果文件列表读取失败");
        }
        var nodes = mapper.readTree(Base64.getDecoder().decode(match.group(1)));
        List<SandboxArtifact> artifacts = new ArrayList<>();
        for (var node : nodes) {
            artifacts.add(new SandboxArtifact(node.path("name").asText(), node.path("size").asLong(),
                    node.path("sha256").asText()));
        }
        if (artifacts.size() > 100 || artifacts.stream().anyMatch(file -> file.size() > 16L * 1024 * 1024)) {
            throw new IOException("沙箱结果文件超过数量或单文件大小限制");
        }
        return artifacts;
    }

    /** 列出沙箱输入目录中的文件；用于确认已准备的数据集是否还在。 */
    public synchronized List<SandboxArtifact> listInputs() throws IOException {
        return listArtifacts(INPUT_DIRECTORY);
    }

    /**
     * 判断沙箱输入目录中是否仍有指定文件；只查存在性，不读取内容。
     *
     * @param fileName 输入文件名
     * @return 文件仍在返回 true
     * @throws IOException 沙箱不可用或读取失败
     */
    public synchronized boolean hasInput(String fileName) throws IOException {
        if (!fileName.matches("[A-Za-z0-9_.-]+") || fileName.equals(".") || fileName.equals("..")) {
            throw new IllegalArgumentException("沙箱文件名无效");
        }
        String encodedName = Base64.getEncoder()
                .encodeToString(fileName.getBytes(StandardCharsets.UTF_8));
        String script = """
                import os, base64
                name = base64.b64decode('%s').decode('utf-8')
                path = os.path.join('%s', name)
                present = os.path.isfile(path) and not os.path.islink(path)
                print('__ERP_EXISTS__:' + ('1' if present else '0'))
                """.formatted(encodedName, INPUT_DIRECTORY);
        var match = Pattern.compile("__ERP_EXISTS__:([01])").matcher(runBootstrap(script));
        if (!match.find()) {
            throw new IOException("沙箱输入文件状态读取失败");
        }
        return "1".equals(match.group(1));
    }

    /** 分块取回本次目录下的普通文件，不跟随符号链接。 */
    public synchronized void readArtifact(String directory, SandboxArtifact artifact, OutputStream target) throws IOException {
        validateArtifactDirectory(directory);
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 不可用", error);
        }
        String encodedName = Base64.getEncoder()
                .encodeToString(artifact.name().getBytes(StandardCharsets.UTF_8));
        int chunkSize = 48 * 1024;
        for (long offset = 0; offset < artifact.size(); offset += chunkSize) {
            String script = """
                    import os, base64, stat
                    root = '%s'
                    name = base64.b64decode('%s').decode('utf-8')
                    if os.path.basename(name) != name or os.path.islink(root):
                        raise ValueError('invalid artifact')
                    fd = os.open(os.path.join(root, name), os.O_RDONLY | os.O_NOFOLLOW)
                    try:
                        if not stat.S_ISREG(os.fstat(fd).st_mode):
                            raise ValueError('invalid artifact')
                        os.lseek(fd, %d, os.SEEK_SET)
                        print('__ERP_ARTIFACT__:' + base64.b64encode(os.read(fd, %d)).decode())
                    finally:
                        os.close(fd)
                    """.formatted(directory, encodedName, offset, chunkSize);
            var match = Pattern.compile("__ERP_ARTIFACT__:([A-Za-z0-9+/=]+)").matcher(runBootstrap(script));
            if (!match.find()) {
                throw new IOException("沙箱结果文件读取不完整");
            }
            byte[] bytes = Base64.getDecoder().decode(match.group(1));
            if (bytes.length != Math.min(chunkSize, artifact.size() - offset)) {
                throw new IOException("沙箱结果文件读取时发生变化");
            }
            digest.update(bytes);
            target.write(bytes);
        }
        if (!HexFormat.of().formatHex(digest.digest()).equals(artifact.sha256())) {
            throw new IOException("沙箱结果文件读取时发生变化");
        }
    }

    /** 只允许读取本次沙箱约定的输入与结果目录。 */
    private void validateArtifactDirectory(String directory) {
        if (!OUTPUT_DIRECTORY.equals(directory) && !INPUT_DIRECTORY.equals(directory)) {
            throw new IllegalArgumentException("沙箱目录无效");
        }
    }

    /** 沙箱输出文件的原始名称、字节数和内容摘要。 */
    public record SandboxArtifact(String name, long size, String sha256) {
    }

    /** 在独立 Python 子进程中执行，并解析明确的成功、错误和超时状态。 */
    public synchronized PythonExecutionResult execute(String code, int timeoutSeconds, int previewBytes) {
        String wrapped = protocol.wrap(code, UUID.randomUUID().toString(), timeoutSeconds, previewBytes);
        return protocol.parse(runBootstrap(wrapped));
    }

    /** 将本次授权数据按块写入任务目录，避免一次请求携带完整大文件。 */
    public synchronized void writeFile(String fileName, byte[] bytes) throws IOException {
        writeFile(fileName, new ByteArrayInputStream(bytes));
    }

    /** 流式准备任务数据，避免将完整数据集加载进 Java 内存。 */
    public synchronized void writeFile(String fileName, InputStream input) throws IOException {
        if (!fileName.matches("[A-Za-z0-9_.-]+") || fileName.equals(".") || fileName.equals("..")) {
            throw new IllegalArgumentException("沙箱文件名无效");
        }
        String path = "/tmp/erp-analysis/input/" + fileName;
        String initialize = """
                import os
                os.makedirs('/tmp/erp-analysis/input', exist_ok=True)
                open('%s', 'wb').close()
                print('__ERP_WRITE_OK__')
                """.formatted(path);
        String initialized = runBootstrap(initialize);
        if (!initialized.contains("__ERP_WRITE_OK__")) {
            throw new IOException("沙箱文件创建失败");
        }
        int chunkSize = 48 * 1024;
        byte[] buffer = new byte[chunkSize];
        int count;
        while ((count = input.read(buffer)) != -1) {
            byte[] chunk = Arrays.copyOf(buffer, count);
            String encoded = Base64.getEncoder().encodeToString(chunk);
            String append = """
                    import base64
                    with open('%s', 'ab') as output:
                        output.write(base64.b64decode('%s'))
                    print('__ERP_WRITE_OK__')
                    """.formatted(path, encoded);
            String response = runBootstrap(append);
            if (!response.contains("__ERP_WRITE_OK__")) {
                throw new IOException("沙箱文件写入失败");
            }
        }
    }

    /** 分块读取包装器生成的输出，不接受任意沙箱路径。 */
    public synchronized void readOutput(PythonExecutionResult result, OutputStream target) throws IOException {
        String path = result.outputFile();
        if (path == null || !path.matches("/tmp/erp-analysis/output-[0-9a-fA-F-]{36}\\.txt")) {
            throw new IllegalArgumentException("沙箱输出路径无效");
        }
        var marker = Pattern.compile("__ERP_FILE__:([A-Za-z0-9+/=]*)");
        int chunkSize = 48 * 1024;
        for (long offset = 0; offset < result.stdoutBytes(); offset += chunkSize) {
            String code = """
                    import base64
                    with open('%s', 'rb') as source:
                        source.seek(%d)
                        chunk = source.read(%d)
                    print('__ERP_FILE__:' + base64.b64encode(chunk).decode('ascii'))
                    """.formatted(path, offset, chunkSize);
            var match = marker.matcher(runBootstrap(code));
            if (!match.find() || match.group(1).isEmpty()) {
                throw new IOException("沙箱输出读取不完整");
            }
            target.write(Base64.getDecoder().decode(match.group(1)));
        }
    }

    /** 执行服务端生成的固定脚本，用于准备和读取任务文件。 */
    synchronized String runBootstrap(String code) {
        if (closed) {
            throw new IllegalStateException("Python 沙箱会话已关闭");
        }
        return client.run(code);
    }

    /** 释放本任务容器及其临时文件。 */
    @Override
    public synchronized void close() {
        if (!closed) {
            closed = true;
            try {
                sandbox.close();
            } finally {
                onClose.accept(this);
            }
        }
    }
}
