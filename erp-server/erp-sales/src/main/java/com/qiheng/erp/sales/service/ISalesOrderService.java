package com.qiheng.erp.sales.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.qiheng.erp.common.result.PageResult;
import com.qiheng.erp.sales.domain.salesorder.dto.SalesOrderPageDto;
import com.qiheng.erp.sales.domain.salesorder.entity.SalesOrder;
import com.qiheng.erp.sales.domain.salesorder.vo.SalesOrderVo;

/**
 * <p>
 * 销售订单主表 服务类
 * </p>
 *
 * @author Li
 * @since 2026-08-10
 */
public interface ISalesOrderService extends IService<SalesOrder> {

    /**
     * 销售订单分页查询（逻辑删除过滤按全局配置自动追加；列表不返回明细数组）
     * @param dto 分页查询参数
     * @return 分页结果集
     */
    PageResult<SalesOrderVo> page(SalesOrderPageDto dto);
}