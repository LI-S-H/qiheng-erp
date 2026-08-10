package com.qiheng.erp.sales.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.qiheng.erp.common.exception.BizException;
import com.qiheng.erp.common.exception.ErrorCode;
import com.qiheng.erp.common.result.PageResult;
import com.qiheng.erp.common.util.IdUtil;
import com.qiheng.erp.common.util.QtyUtil;
import com.qiheng.erp.sales.domain.salesorder.dto.SalesOrderPageDto;
import com.qiheng.erp.sales.domain.salesorder.entity.SalesOrder;
import com.qiheng.erp.sales.domain.salesorder.entity.SalesOrderItem;
import com.qiheng.erp.sales.domain.salesorder.vo.SalesOrderDetailVo;
import com.qiheng.erp.sales.domain.salesorder.vo.SalesOrderItemVo;
import com.qiheng.erp.sales.domain.salesorder.vo.SalesOrderVo;
import com.qiheng.erp.sales.mapper.SalesOrderItemMapper;
import com.qiheng.erp.sales.mapper.SalesOrderMapper;
import com.qiheng.erp.sales.service.ISalesOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

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
    @Autowired
    private SalesOrderMapper salesOrderMapper;
    @Autowired
    private SalesOrderItemMapper salesOrderItemMapper;

    /**
     * 销售订单分页查询（逻辑删除过滤按全局配置自动追加）
     * @param dto 分页查询参数
     * @return 分页结果集
     */
    @Override
    public PageResult<SalesOrderVo> page(SalesOrderPageDto dto) {
        Long customerId = IdUtil.parseOptionalLongId(dto.getCustomerId(), "客户ID");
        Long warehouseId = IdUtil.parseOptionalLongId(dto.getWarehouseId(), "出库仓库ID");
        LambdaQueryWrapper<SalesOrder> wrapper = new LambdaQueryWrapper<SalesOrder>()
                .like(StrUtil.isNotBlank(dto.getSalesNo()), SalesOrder::getSalesNo, dto.getSalesNo())
                .eq(customerId != null, SalesOrder::getCustomerId, customerId)
                .eq(warehouseId != null, SalesOrder::getWarehouseId, warehouseId)
                .eq(StrUtil.isNotBlank(dto.getStatus()), SalesOrder::getStatus, dto.getStatus())
                .orderByDesc(SalesOrder::getCreateTime);
        Page<SalesOrder> result = salesOrderMapper.selectPage(dto.toPage(), wrapper);
        return PageResult.of(
                result.getRecords().stream().map(this::toVo).toList(),
                (int) result.getTotal(),
                (int) result.getCurrent(),
                (int) result.getSize()
        );
    }

    /**
     * 获取销售订单详情（主表 + 明细数组）
     * @param salesOrderId 销售订单ID
     * @return 销售订单详情VO
     */
    @Override
    public SalesOrderDetailVo getDetail(Long salesOrderId) {
        SalesOrder order = salesOrderMapper.selectById(salesOrderId);
        if (order == null) {
            throw new BizException(ErrorCode.DATA_NOT_FOUND);
        }
        List<SalesOrderItem> items = salesOrderItemMapper.selectList(
                new LambdaQueryWrapper<SalesOrderItem>()
                        .eq(SalesOrderItem::getSalesOrderId, salesOrderId)
        );
        SalesOrderDetailVo detail = new SalesOrderDetailVo();
        BeanUtil.copyProperties(toVo(order), detail);
        detail.setItems(items.stream().map(this::toItemVo).toList());
        return detail;
    }

    /**
     * 实体转 VO，订单总金额从 100 倍存储值还原为业务小数
     */
    private SalesOrderVo toVo(SalesOrder entity) {
        SalesOrderVo vo = BeanUtil.copyProperties(entity, SalesOrderVo.class);
        vo.setSalesOrderId(entity.getId());
        vo.setTotalAmount(QtyUtil.toDecimal(entity.getTotalAmount()));
        return vo;
    }

    /**
     * 明细实体转 VO，数量与金额字段均从 100 倍存储值还原为业务小数
     */
    private SalesOrderItemVo toItemVo(SalesOrderItem entity) {
        SalesOrderItemVo vo = BeanUtil.copyProperties(entity, SalesOrderItemVo.class);
        vo.setSalesOrderItemId(entity.getId());
        vo.setQuantity(QtyUtil.toDecimal(entity.getQuantity()));
        vo.setLockedQty(QtyUtil.toDecimal(entity.getLockedQty()));
        vo.setOutboundQty(QtyUtil.toDecimal(entity.getOutboundQty()));
        vo.setUnitPrice(QtyUtil.toDecimal(entity.getUnitPrice()));
        vo.setTotalAmount(QtyUtil.toDecimal(entity.getTotalAmount()));
        return vo;
    }
}