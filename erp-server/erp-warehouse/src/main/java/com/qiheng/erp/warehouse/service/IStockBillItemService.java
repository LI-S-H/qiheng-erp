package com.qiheng.erp.warehouse.service;

import com.qiheng.erp.warehouse.domain.stockbill.entity.StockBillItem;
import com.qiheng.erp.warehouse.domain.stockbill.vo.StockBillDetailsVo;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 * 库存流水凭证明细表 服务类
 * </p>
 *
 * @author Li
 * @since 2026-07-19
 */
public interface IStockBillItemService extends IService<StockBillItem> {

    /**
     * 根据库存流水凭证ID查询详情（包含明细列表）
     * @param stockLedgerId 库存流水凭证ID
     * @return 库存流水详情
     */
    StockBillDetailsVo getDetailsById(Long stockLedgerId);

}