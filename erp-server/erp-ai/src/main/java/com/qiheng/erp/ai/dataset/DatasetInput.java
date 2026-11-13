package com.qiheng.erp.ai.dataset;

import java.io.IOException;
import java.io.InputStream;

/** 已完成归属、发布状态和权限校验的数据集流。 */
public record DatasetInput(DatasetRecord record, InputStream stream) implements AutoCloseable {
    @Override
    public void close() throws IOException {
        stream.close();
    }
}
