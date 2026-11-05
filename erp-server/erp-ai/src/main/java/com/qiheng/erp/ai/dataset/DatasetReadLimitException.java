package com.qiheng.erp.ai.dataset;

/** 数据内容超出工具单次读取预算，允许调用方省略可选预览。 */
public final class DatasetReadLimitException extends IllegalStateException {
    public DatasetReadLimitException(String message) {
        super(message);
    }
}
