package com.qiheng.erp.purchase.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.qiheng.erp.purchase.domain.supplierscore.entity.SupplierScoreChangeLog;

public interface SupplierScoreChangeLogMapper extends BaseMapper<SupplierScoreChangeLog> {

    /**
     * 查询指定日期下 batchNo 的最大 5 位序号,供 BillNoGenerator 当 DB 回查函数。
     *
     * @param dayPrefix 例:SC20260923
     * @return 当天最大序号,无数据返回 0
     */
    long findMaxBatchNoSequence(String dayPrefix);
}
