package com.qiheng.erp.ai.python.result;

/** 分析结果不存在或已经过期，调用方可提示重新分析。 */
public final class AnalysisResultUnavailableException extends IllegalStateException {
    public AnalysisResultUnavailableException(String message) {
        super(message);
    }
}
