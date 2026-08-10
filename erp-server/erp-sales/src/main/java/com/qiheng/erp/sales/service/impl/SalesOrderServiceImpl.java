package com.qiheng.erp.sales.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.qiheng.erp.common.result.PageResult;
import com.qiheng.erp.common.util.IdUtil;
import com.qiheng.erp.common.util.QtyUtil;
import com.qiheng.erp.sales.domain.salesorder.dto.SalesOrderPageDto;
import com.qiheng.erp.sales.domain.salesorder.entity.SalesOrder;
import com.qiheng.erp.sales.domain.salesorder.vo.SalesOrderVo;
import com.qiheng.erp.sales.mapper.SalesOrderMapper;
import com.qiheng.erp.sales.service.ISalesOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

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
     * 实体转 VO，订单总金额从 100 倍存储值还原为业务小数
     */
    private SalesOrderVo toVo(SalesOrder entity) {
        SalesOrderVo vo = BeanUtil.copyProperties(entity, SalesOrderVo.class);
        vo.setSalesOrderId(entity.getId());
        vo.setTotalAmount(totalAmountToDecimal(entity.getTotalAmount()));
        return vo;
    }

    /**
     * 订单总金额 100 倍存储值转业务小数
     */
    private BigDecimal totalAmountToDecimal(Integer totalAmount) {
        if (totalAmount == null) {
            return null;
        }
        return QtyUtil.toDecimal(BigDecimal.valueOf(totalAmount));
    }
}