package com.qiheng.erp.ai.dataset;

/** 数据集引用的当前授权检查。 */
interface DatasetAccess {
    void verify(DatasetRecord record);
}
