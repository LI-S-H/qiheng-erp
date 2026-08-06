package com.qiheng.erp.returnorder.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.qiheng.erp.returnorder.domain.entity.ReturnOrderItem;
import com.qiheng.erp.returnorder.mapper.ReturnOrderItemMapper;
import com.qiheng.erp.returnorder.service.IReturnOrderItemService;
import org.springframework.stereotype.Service;

/**
 * <p>
 * 退货单明细表 服务实现类
 * </p>
 *
 * @author Li
 * @since 2026-07-31
 */
@Service
public class ReturnOrderItemServiceImpl extends ServiceImpl<ReturnOrderItemMapper, ReturnOrderItem>
        implements IReturnOrderItemService {
}
