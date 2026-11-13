package com.qiheng.erp.ai.python.sandbox;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 包装 Python 子进程，并从沙箱响应中提取可靠的执行结果。
 *
 * @author Li
 * @since 2026-11-10
 */
public final class PythonExecutionProtocol {
    private static final String MARKER = "__ERP_PYTHON_RESULT_V1__:";
    private static final int MAX_OUTPUT_BYTES = 16 * 1024 * 1024;
    private static final Pattern RESULT = Pattern.compile(Pattern.quote(MARKER) + "([A-Za-z0-9+/=]+)");
    private final ObjectMapper mapper;

    /** 配置执行结果的 JSON 解析器。 */
    public PythonExecutionProtocol(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** 将用户代码放进独立子进程，避免依赖底层工具的错误标记。 */
    public String wrap(String code, String runId, int timeoutSeconds, int previewBytes) {
        return wrap(code, runId, timeoutSeconds, previewBytes, MAX_OUTPUT_BYTES);
    }

    /** 用进程组清理子进程，并限制单个输出文件的最大大小。 */
    String wrap(String code, String runId, int timeoutSeconds, int previewBytes, int maxOutputBytes) {
        String safeRunId = UUID.fromString(runId).toString();
        String encoded = Base64.getEncoder().encodeToString(code.getBytes(StandardCharsets.UTF_8));
        return """
                import base64, json, os, signal, subprocess, sys
                root = '/tmp/erp-analysis'
                os.makedirs(root, exist_ok=True)
                output = root + '/output-%s.txt'
                error = root + '/error-%s.txt'
                encoded_code = '%s'
                child_code = ("import base64, resource\\n"
                              "resource.setrlimit(resource.RLIMIT_FSIZE, (%d, %d))\\n"
                              "exec(compile(base64.b64decode('" + encoded_code + "'), '<analysis>', 'exec'))")
                result = {'success': False, 'stdout': '', 'stderr': '', 'stdoutBytes': 0,
                          'stdoutTruncated': False, 'outputFile': output, 'errorType': None}
                process = None
                try:
                    with open(output, 'wb') as out, open(error, 'wb') as err:
                        process = subprocess.Popen([sys.executable, '-c', child_code], stdout=out, stderr=err,
                                                   start_new_session=True)
                        process.wait(timeout=%d)
                    result['success'] = process.returncode == 0
                    if not result['success']:
                        result['errorType'] = 'PYTHON_ERROR'
                except subprocess.TimeoutExpired:
                    result['errorType'] = 'TIMEOUT'
                except Exception as exc:
                    result['errorType'] = type(exc).__name__
                    result['stderr'] = str(exc)
                finally:
                    if process is not None:
                        try:
                            os.killpg(process.pid, signal.SIGKILL)
                        except ProcessLookupError:
                            pass
                        process.wait()
                if os.path.exists(output):
                    result['stdoutBytes'] = os.path.getsize(output)
                    result['stdoutTruncated'] = result['stdoutBytes'] > %d
                    with open(output, 'rb') as out:
                        result['stdout'] = out.read(%d).decode('utf-8', errors='replace')
                if os.path.exists(error):
                    with open(error, 'rb') as err:
                        result['stderr'] += err.read(%d).decode('utf-8', errors='replace')
                if not result['success'] and result['errorType'] == 'PYTHON_ERROR' and (
                        os.path.getsize(output) >= %d or os.path.getsize(error) >= %d):
                    result['errorType'] = 'OUTPUT_LIMIT'
                encoded = base64.b64encode(json.dumps(result, ensure_ascii=False).encode('utf-8')).decode('ascii')
                print('%s' + encoded)
                """.formatted(safeRunId, safeRunId, encoded, maxOutputBytes, maxOutputBytes, timeoutSeconds,
                previewBytes, previewBytes, previewBytes, maxOutputBytes, maxOutputBytes, MARKER);
    }

    /** 只读取包装器生成的最后一个结果，保留 Python 错误和截断状态。 */
    public PythonExecutionResult parse(String response) {
        var matcher = RESULT.matcher(response);
        String payload = null;
        while (matcher.find()) {
            payload = matcher.group(1);
        }
        if (payload == null) {
            throw new IllegalStateException("沙箱未返回 Python 执行结果");
        }
        try {
            byte[] json = Base64.getDecoder().decode(payload);
            return mapper.readValue(json, PythonExecutionResult.class);
        } catch (IllegalArgumentException | IOException error) {
            throw new IllegalStateException("沙箱返回的 Python 执行结果无效", error);
        }
    }
}
