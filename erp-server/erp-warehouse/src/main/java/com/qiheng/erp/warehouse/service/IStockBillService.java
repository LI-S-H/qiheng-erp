package com.qiheng.erp.warehouse.service;

import com.qiheng.erp.warehouse.domain.stockbill.dto.StockBillPageDto;
import com.qiheng.erp.warehouse.domain.stockbill.entity.StockBill;
import com.qiheng.erp.warehouse.domain.stockbill.vo.StockBillPageVo;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 * 库存流水凭证主表 服务类
 * </p>
 *
 * @author Li
 * @since 2026-07-19
 */
public interface IStockBillService extends IService<StockBill> {

    /**
     * 分页查询库存流水记录
     * @param dto 分页查询请求
     * @return 库存流水分页结果
     */
    StockBillPageVo page(StockBillPageDto dto);

}