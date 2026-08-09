package com.qiheng.erp.sales.service.impl;

import com.qiheng.erp.sales.domain.salesorder.entity.SalesOrder;
import com.qiheng.erp.sales.mapper.SalesOrderMapper;
import com.qiheng.erp.sales.service.ISalesOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

/**
 * <p>
 * 销售订单主表 服务实现类
 * </p>
 *
 * @author Li
 * @since 2026-08-10
 */
@Service
public class SalesOrderServiceImpl extends ServiceImpl<SalesOrderMapper, SalesOrder> implements ISalesOrderService {

}
