package com.qiheng.erp.ai.python.sandbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PythonExecutionProtocolTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final PythonExecutionProtocol protocol = new PythonExecutionProtocol(mapper);

    @Test
    void parsesFailureEvenWhenOuterToolReportsSuccess() throws Exception {
        var expected = new PythonExecutionResult(false, "部分输出", "执行失败", 12, false,
                "/tmp/erp-analysis/output.txt", "PYTHON_ERROR");
        String payload = Base64.getEncoder().encodeToString(mapper.writeValueAsBytes(expected));
        String response = "{\"isError\":false,\"text\":\"__ERP_PYTHON_RESULT_V1__:" + payload + "\"}";

        assertEquals(expected, protocol.parse(response));
    }

    @Test
    void preservesTruncationAndUsesLastWrappedResult() throws Exception {
        var expected = new PythonExecutionResult(true, "结果", "", 100, true,
                "/tmp/erp-analysis/output.txt", null);
        String payload = Base64.getEncoder().encodeToString(mapper.writeValueAsBytes(expected));
        assertEquals(expected, protocol.parse("__ERP_PYTHON_RESULT_V1__:ZmFrZQ==\n__ERP_PYTHON_RESULT_V1__:" + payload));
        assertThrows(IllegalStateException.class, () -> protocol.parse("无结果"));
    }

    @Test
    void encodesCodeInsteadOfEmbeddingItInTheWrapper() {
        String code = "print('中文 \\\" and quotes')";
        String wrapped = protocol.wrap(code, UUID.randomUUID().toString(), 3, 100);
        assertFalse(wrapped.contains(code));
        assertTrue(wrapped.contains(Base64.getEncoder().encodeToString(code.getBytes(StandardCharsets.UTF_8))));
        assertThrows(IllegalArgumentException.class, () -> protocol.wrap(code, "../invalid", 3, 100));
    }
}
