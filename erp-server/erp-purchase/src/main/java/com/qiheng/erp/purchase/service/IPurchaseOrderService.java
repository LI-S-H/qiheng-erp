package com.qiheng.erp.purchase.service;

import com.qiheng.erp.common.result.PageResult;
import com.qiheng.erp.purchase.domain.purchaseorder.dto.PurchaseOrderCreateDto;
import com.qiheng.erp.purchase.domain.purchaseorder.dto.PurchaseOrderPageDto;
import com.qiheng.erp.purchase.domain.purchaseorder.dto.PurchaseOrderUpdateDto;
import com.qiheng.erp.purchase.domain.purchaseorder.entity.PurchaseOrder;
import com.qiheng.erp.purchase.domain.purchaseorder.vo.PurchaseOrderDetailVo;
import com.qiheng.erp.purchase.domain.purchaseorder.vo.PurchaseOrderVo;
import com.qiheng.erp.warehouse.domain.inbound.entity.InboundBill;
import com.qiheng.erp.warehouse.domain.inbound.entity.InboundBillItem;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

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

    /**
     * 获取采购订单详情（主表 + 明细列表）
     * @param purchaseOrderId 采购订单ID
     * @return 采购订单详情VO
     */
    PurchaseOrderDetailVo getDetail(Long purchaseOrderId);

    /**
     * 新增采购订单草稿
     * @param dto 新增采购订单请求DTO
     * @return 采购订单详情VO
     */
    PurchaseOrderDetailVo createDraft(PurchaseOrderCreateDto dto);

    /**
     * 编辑采购订单（全量替换明细，重算总金额）
     * @param purchaseOrderId 采购订单ID
     * @param dto 编辑采购订单请求DTO
     * @return 采购订单详情VO
     */
    PurchaseOrderDetailVo update(Long purchaseOrderId, PurchaseOrderUpdateDto dto);

    /**
     * 提交采购订单（DRAFT -> SUBMITTED）
     * @param purchaseOrderId 采购订单ID
     * @param version 乐观锁版本号
     */
    void submit(Long purchaseOrderId, Integer version);

    /**
     * 审核采购订单（SUBMITTED -> APPROVED），并生成 PURCHASE_IN 待确认入库单
     * @param purchaseOrderId 采购订单ID
     * @param version 乐观锁版本号
     */
    void approve(Long purchaseOrderId, Integer version);

    /**
     * 入库确认后回写采购订单的累计入库数量与状态。
     * 该方法仅由仓储的来源单回写扩展点调用，不对外暴露为 HTTP 接口。
     *
     * @param bill 已确认的采购入库单
     * @param items 已确认的入库明细
     */
    void handleInboundConfirmation(InboundBill bill, List<InboundBillItem> items);

    /**
     * 取消采购订单（DRAFT/SUBMITTED -> CANCELLED）
     * @param purchaseOrderId 采购订单ID
     * @param version 乐观锁版本号
     */
    void cancel(Long purchaseOrderId, Integer version);
}
