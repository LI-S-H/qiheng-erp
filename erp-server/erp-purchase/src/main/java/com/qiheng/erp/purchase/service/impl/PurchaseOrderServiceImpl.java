package com.qiheng.erp.purchase.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.qiheng.erp.common.util.IdUtil;
import com.qiheng.erp.common.result.PageResult;
import com.qiheng.erp.purchase.domain.purchaseorder.dto.PurchaseOrderPageDto;
import com.qiheng.erp.purchase.domain.purchaseorder.entity.PurchaseOrder;
import com.qiheng.erp.purchase.domain.purchaseorder.vo.PurchaseOrderVo;
import com.qiheng.erp.purchase.mapper.PurchaseOrderMapper;
import com.qiheng.erp.purchase.service.IPurchaseOrderService;
import org.springframework.stereotype.Service;

/**
 * <p>
 * 采购订单主表 服务实现类
 * </p>
 *
 * @author Li
 * @since 2026-07-31
 */
@Service
public class PurchaseOrderServiceImpl extends ServiceImpl<PurchaseOrderMapper, PurchaseOrder>
        implements IPurchaseOrderService {

    /**
     * 采购订单分页查询
     * @param dto 分页查询参数DTO
     * @return 分页查询结果VO
     */
    @Override
    public PageResult<PurchaseOrderVo> page(PurchaseOrderPageDto dto) {
        Long supplierId = IdUtil.parseOptionalLongId(dto.getSupplierId(), "供应商ID");
        Long warehouseId = IdUtil.parseOptionalLongId(dto.getWarehouseId(), "仓库ID");
        String statusName = dto.getStatus() != null ? dto.getStatus().name() : null;
        // 构建查询条件
        LambdaQueryWrapper<PurchaseOrder> wrapper = new LambdaQueryWrapper<PurchaseOrder>()
                .like(StrUtil.isNotBlank(dto.getPurchaseNo()), PurchaseOrder::getPurchaseNo, dto.getPurchaseNo())
                .eq(supplierId != null, PurchaseOrder::getSupplierId, supplierId)
                .eq(warehouseId != null, PurchaseOrder::getWarehouseId, warehouseId)
                .eq(statusName != null, PurchaseOrder::getStatus, statusName)
                .orderByDesc(PurchaseOrder::getCreateTime);
        // 执行分页查询
        Page<PurchaseOrder> page = this.page(dto.toPage(), wrapper);
        return PageResult.of(
                page.getRecords().stream().map(this::toVo).toList(),
                (int) page.getTotal(),
                (int) page.getCurrent(),
                (int) page.getSize()
        );
    }

    /**
     * 实体转列表VO
     * @param entity 采购订单实体
     * @return 列表VO
     */
    private PurchaseOrderVo toVo(PurchaseOrder entity) {
        PurchaseOrderVo vo = new PurchaseOrderVo();
        BeanUtil.copyProperties(entity, vo, "id");
        vo.setPurchaseOrderId(entity.getId());
        return vo;
    }
}
