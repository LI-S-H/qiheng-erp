package com.qiheng.erp.warehouse.service;

import com.qiheng.erp.warehouse.domain.dto.OutboundBillCreateDto;
import com.qiheng.erp.warehouse.domain.dto.OutboundBillPageDto;
import com.qiheng.erp.warehouse.domain.entity.OutboundBill;
import com.qiheng.erp.warehouse.domain.vo.OutboundBillDetailVo;
import com.qiheng.erp.warehouse.domain.vo.OutboundBillPageVo;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 * 出库单主表 服务类
 * </p>
 *
 * @author Li
 * @since 2026-07-26
 */
public interface IOutboundBillService extends IService<OutboundBill> {

    /**
     * 分页查询出库单记录
     * @param dto 分页查询请求
     * @return 出库单分页结果
     */
    OutboundBillPageVo page(OutboundBillPageDto dto);

    /**
     * 根据ID查询出库单详情
     * @param outboundBillId 出库单ID（字符串形式）
     * @return 出库单详情
     */
    OutboundBillDetailVo getDetailById(String outboundBillId);

    /**
     * 新增手工出库单草稿
     * @param dto 创建请求
     * @return 出库单详情
     */
    OutboundBillDetailVo createDraft(OutboundBillCreateDto dto);
}
