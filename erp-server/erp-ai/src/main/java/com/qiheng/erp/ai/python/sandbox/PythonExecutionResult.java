package com.qiheng.erp.ai.python.sandbox;

/** Python 进程的执行结果；完整输出保存在当前任务沙箱内。 */
public record PythonExecutionResult(boolean success, String stdout, String stderr,
                                    long stdoutBytes, boolean stdoutTruncated,
                                    String outputFile, String errorType) {
}
