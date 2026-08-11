package com.qiheng.erp.sales.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.qiheng.erp.common.result.PageResult;
import com.qiheng.erp.sales.domain.salesorder.dto.SalesOrderCreateDto;
import com.qiheng.erp.sales.domain.salesorder.dto.SalesOrderPageDto;
import com.qiheng.erp.sales.domain.salesorder.entity.SalesOrder;
import com.qiheng.erp.sales.domain.salesorder.vo.SalesOrderDetailVo;
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

    /**
     * 获取销售订单详情（主表 + 明细数组）
     * @param salesOrderId 销售订单ID
     * @return 销售订单详情VO
     */
    SalesOrderDetailVo getDetail(Long salesOrderId);

    /**
     * 新增销售订单草稿（后端生成销售单号、写入客户/仓库/产品快照、数量×100 持久化、重算订单总金额）
     * @param dto 草稿新增请求 DTO
     * @return 新增后的销售订单详情VO（含主表 + 明细）
     */
    SalesOrderDetailVo createDraft(SalesOrderCreateDto dto);

    /**
     * 提交销售订单（DRAFT → SUBMITTED，数据库行锁锁定可用库存，同步更新明细与主表状态）
     * @param salesOrderId 销售订单ID
     * @param version 乐观锁版本号
     */
    void submit(Long salesOrderId, Integer version);
}