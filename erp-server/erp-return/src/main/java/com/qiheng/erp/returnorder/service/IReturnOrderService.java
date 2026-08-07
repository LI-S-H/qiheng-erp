package com.qiheng.erp.returnorder.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.qiheng.erp.common.result.PageResult;
import com.qiheng.erp.returnorder.domain.port.ReturnType;
import com.qiheng.erp.returnorder.domain.dto.ReturnOrderCreateDto;
import com.qiheng.erp.returnorder.domain.dto.ReturnOrderPageDto;
import com.qiheng.erp.returnorder.domain.dto.ReturnOrderUpdateDto;
import com.qiheng.erp.returnorder.domain.entity.ReturnOrder;
import com.qiheng.erp.returnorder.domain.vo.ReturnOrderDetailVo;
import com.qiheng.erp.returnorder.domain.vo.ReturnOrderItemVo;
import com.qiheng.erp.returnorder.domain.vo.ReturnOrderVo;
import com.qiheng.erp.returnorder.domain.vo.ReturnableSourceOrderVo;
import com.qiheng.erp.warehouse.domain.outbound.entity.OutboundBill;
import com.qiheng.erp.warehouse.domain.outbound.entity.OutboundBillItem;

import java.util.List;

/**
 * 统一退货单服务。
 *
 * <p>该服务维护退货单自身的数据和处理进度；采购、销售来源数据通过
 * {@link com.qiheng.erp.returnorder.domain.port.ReturnSourceProvider} 获取，避免反向依赖具体业务模块。</p>
 */
public interface IReturnOrderService extends IService<ReturnOrder> {

    /** 分页查询退货单主信息。 */
    PageResult<ReturnOrderVo> page(ReturnOrderPageDto dto);

    /** 查询退货单及其明细。 */
    ReturnOrderDetailVo getDetail(Long returnOrderId);

    /** 搜索可作为退货来源的订单，并计算该订单的剩余可退数量。 */
    List<ReturnableSourceOrderVo> searchSourceOrders(ReturnType returnType, String sourceOrderNo, int limit);

    /** 查询来源订单中可退的商品明细。 */
    List<ReturnOrderItemVo> listSourceItems(ReturnType returnType, Long sourceOrderId);

    /** 创建退货单草稿，返回包含明细的详情。 */
    ReturnOrderDetailVo createDraft(ReturnOrderCreateDto dto);

    /** 编辑退货单草稿（DRAFT/SUBMITTED 可编辑），全量替换明细。 */
    ReturnOrderDetailVo update(Long returnOrderId, ReturnOrderUpdateDto dto);

    /** 采购退货出库确认后，回写退货单明细已处理数量和退货单状态。 */
    void handleOutboundConfirmation(OutboundBill bill, List<OutboundBillItem> items);
}
