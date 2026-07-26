package com.qiheng.erp.warehouse.service;

import com.qiheng.erp.common.dto.OptimisticLockVersionDto;
import com.qiheng.erp.warehouse.domain.dto.InboundBillCreateDto;
import com.qiheng.erp.warehouse.domain.dto.InboundBillPageDto;
import com.qiheng.erp.warehouse.domain.dto.StockBillItemUpdateDto;
import com.qiheng.erp.warehouse.domain.entity.InboundBill;
import com.qiheng.erp.warehouse.domain.vo.InboundBillDetailVo;
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

    /**
     * 根据ID查询入库单详情
     * @param inboundBillId 入库单ID（字符串形式）
     * @return 入库单详情
     */
    InboundBillDetailVo getDetailById(String inboundBillId);

    /**
     * 新增手工入库单草稿
     * @param dto 创建请求
     * @return 入库单详情
     */
    InboundBillDetailVo createDraft(InboundBillCreateDto dto);

    /**
     * 编辑入库单草稿或待确认单
     * @param inboundBillId 入库单ID
     * @param dto 编辑请求
     * @return 入库单详情
     */
    InboundBillDetailVo updateDraft(String inboundBillId, StockBillItemUpdateDto dto);

    /**
     * 提交入库单草稿为待确认
     * @param inboundBillId 入库单ID
     * @param dto 乐观锁版本号请求
     * @return 入库单详情
     */
    InboundBillDetailVo submitDraft(String inboundBillId, OptimisticLockVersionDto dto);

    /**
     * 取消入库单草稿或待确认单
     * @param inboundBillId 入库单ID
     * @param dto 乐观锁版本号请求
     * @return 入库单详情
     */
    InboundBillDetailVo cancelBill(String inboundBillId, OptimisticLockVersionDto dto);

    /**
     * 确认入库单：生成库存流水并增加库存
     * @param inboundBillId 入库单ID
     * @param dto 乐观锁版本号请求
     * @return 入库单详情
     */
    InboundBillDetailVo confirmBill(String inboundBillId, OptimisticLockVersionDto dto);
}