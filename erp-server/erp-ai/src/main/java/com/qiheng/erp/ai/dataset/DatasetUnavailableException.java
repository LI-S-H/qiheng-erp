package com.qiheng.erp.ai.dataset;

/** 数据集不存在、已过期或文件不可用，调用方可以提示重新取数。 */
public final class DatasetUnavailableException extends IllegalStateException {
    public DatasetUnavailableException(String message) {
        super(message);
    }
}
