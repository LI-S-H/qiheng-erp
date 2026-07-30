package com.qiheng.erp.purchase.service;

import com.qiheng.erp.common.result.PageResult;
import com.qiheng.erp.purchase.domain.purchaseorder.dto.PurchaseOrderPageDto;
import com.qiheng.erp.purchase.domain.purchaseorder.entity.PurchaseOrder;
import com.qiheng.erp.purchase.domain.purchaseorder.vo.PurchaseOrderVo;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 * 采购订单主表 服务类
 * </p>
 *
 * @author Li
 * @since 2026-07-31
 */
public interface IPurchaseOrderService extends IService<PurchaseOrder> {

    /**
     * 采购订单分页查询
     * @param dto 分页查询参数DTO
     * @return 分页查询结果VO
     */
    PageResult<PurchaseOrderVo> page(PurchaseOrderPageDto dto);
}
