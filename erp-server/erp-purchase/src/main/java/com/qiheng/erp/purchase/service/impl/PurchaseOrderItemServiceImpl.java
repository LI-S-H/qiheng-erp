package com.qiheng.erp.purchase.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.qiheng.erp.purchase.domain.purchaseorder.entity.PurchaseOrderItem;
import com.qiheng.erp.purchase.mapper.PurchaseOrderItemMapper;
import com.qiheng.erp.purchase.service.IPurchaseOrderItemService;
import org.springframework.stereotype.Service;

/**
 * <p>
 * 采购订单明细表 服务实现类
 * </p>
 *
 * @author Li
 * @since 2026-07-31
 */
@Service
public class PurchaseOrderItemServiceImpl extends ServiceImpl<PurchaseOrderItemMapper, PurchaseOrderItem> implements IPurchaseOrderItemService {

}
