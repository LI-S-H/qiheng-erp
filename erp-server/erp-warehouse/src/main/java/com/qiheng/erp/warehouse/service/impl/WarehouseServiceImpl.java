package com.qiheng.erp.warehouse.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.qiheng.erp.common.result.PageResult;
import com.qiheng.erp.warehouse.domain.dto.WarehousePageDto;
import com.qiheng.erp.warehouse.domain.entity.Warehouse;
import com.qiheng.erp.warehouse.domain.vo.WarehouseVo;
import com.qiheng.erp.warehouse.mapper.WarehouseMapper;
import com.qiheng.erp.warehouse.service.IWarehouseService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import jakarta.validation.constraints.NotNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * <p>
 * 仓库表 服务实现类
 * </p>
 *
 * @author Li
 * @since 2026-07-16
 */
@Service
public class WarehouseServiceImpl extends ServiceImpl<WarehouseMapper, Warehouse> implements IWarehouseService {

    @Autowired
    private WarehouseMapper warehouseMapper;

    /**
     * 分页查询仓库
     * @param dto 分页查询参数
     * @return 分页结果集
     */
    @Override
    public PageResult<WarehouseVo> page(WarehousePageDto dto) {
        // 构建查询条件
        LambdaQueryWrapper<Warehouse> wrapper = new LambdaQueryWrapper<Warehouse>()
                .like(StrUtil.isNotBlank(dto.getWarehouseCode()), Warehouse::getWarehouseCode, dto.getWarehouseCode())
                .like(StrUtil.isNotBlank(dto.getWarehouseName()), Warehouse::getWarehouseName, dto.getWarehouseName())
                .like(StrUtil.isNotBlank(dto.getContactName()), Warehouse::getContactName, dto.getContactName())
                .like(StrUtil.isNotBlank(dto.getContactPhone()), Warehouse::getContactPhone, dto.getContactPhone())
                .eq(dto.getStatus() != null, Warehouse::getStatus, dto.getStatus())
                .orderByDesc(Warehouse::getCreateTime);

        // 执行分页查询
        Page<Warehouse> page = dto.toPage();
        page.setSearchCount(false);
        Page<Warehouse> result = warehouseMapper.selectPage(page, wrapper);

        // 转换为VO
        List<WarehouseVo> voList = result.getRecords().stream().map(this::getWarehouseVo).collect(Collectors.toList());

        return PageResult.of(voList, (int) result.getTotal(), (int) result.getCurrent(), (int) result.getSize());
    }

    /**
     * 根据ID查询仓库详情
     * @param id 仓库ID
     * @return 仓库VO
     */
    @Override
    public WarehouseVo getDetailById(Long id) {
        Warehouse warehouse = warehouseMapper.selectById(id);
        if (warehouse == null) {
            return null;
        }
        return getWarehouseVo(warehouse);
    }

    @NotNull
    private WarehouseVo getWarehouseVo(Warehouse w) {
        WarehouseVo vo = new WarehouseVo();
        vo.setWarehouseId(w.getId());
        vo.setWarehouseCode(w.getWarehouseCode());
        vo.setWarehouseName(w.getWarehouseName());
        vo.setContactName(w.getContactName());
        vo.setContactPhone(w.getContactPhone());
        vo.setAddress(w.getAddress());
        vo.setStatus(w.getStatus());
        vo.setVersion(w.getVersion());
        vo.setRemark(w.getRemark());
        vo.setCreateTime(w.getCreateTime());
        vo.setUpdateTime(w.getUpdateTime());
        return vo;
    }
}