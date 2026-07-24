package com.qiheng.erp.warehouse.service;

import com.qiheng.erp.warehouse.domain.dto.InboundBillPageDto;
import com.qiheng.erp.warehouse.domain.entity.InboundBill;
import com.qiheng.erp.warehouse.domain.vo.InboundBillPageVo;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 * 入库单主表 服务类
 * </p>
 *
 * @author Li
 * @since 2026-07-23
 */
public interface IInboundBillService extends IService<InboundBill> {

    /**
     * 分页查询入库单记录
     * @param dto 分页查询请求
     * @return 入库单分页结果
     */
    InboundBillPageVo page(InboundBillPageDto dto);
}