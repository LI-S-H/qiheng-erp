package com.qiheng.erp.warehouse.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.qiheng.erp.common.annotation.DistributedLock;
import com.qiheng.erp.common.exception.BizException;
import com.qiheng.erp.common.exception.ErrorCode;
import com.qiheng.erp.common.result.PageResult;
import com.qiheng.erp.warehouse.domain.dto.WarehouseBatchStatusDto;
import com.qiheng.erp.warehouse.domain.dto.WarehousePageDto;
import com.qiheng.erp.warehouse.domain.entity.Warehouse;
import com.qiheng.erp.warehouse.domain.vo.WarehouseVo;
import com.qiheng.erp.warehouse.mapper.WarehouseMapper;
import com.qiheng.erp.warehouse.service.IWarehouseService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import jakarta.validation.constraints.NotNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
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

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 分页查询仓库
     * @param dto 分页查询参数
     * @return 分页结果集
     */
    @Override
    public PageResult<WarehouseVo> page(WarehousePageDto dto) {
        LambdaQueryWrapper<Warehouse> wrapper = new LambdaQueryWrapper<Warehouse>()
                .like(StrUtil.isNotBlank(dto.getWarehouseCode()), Warehouse::getWarehouseCode, dto.getWarehouseCode())
                .like(StrUtil.isNotBlank(dto.getWarehouseName()), Warehouse::getWarehouseName, dto.getWarehouseName())
                .like(StrUtil.isNotBlank(dto.getContactName()), Warehouse::getContactName, dto.getContactName())
                .like(StrUtil.isNotBlank(dto.getContactPhone()), Warehouse::getContactPhone, dto.getContactPhone())
                .eq(dto.getStatus() != null, Warehouse::getStatus, dto.getStatus())
                .orderByDesc(Warehouse::getCreateTime);

        Page<Warehouse> page = dto.toPage();
        page.setSearchCount(false);
        Page<Warehouse> result = warehouseMapper.selectPage(page, wrapper);

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

    /**
     * 仓库新增
     * @param warehouse 仓库实体
     * @return 仓库VO
     */
    @Override
    @DistributedLock(key = "'warehouse:lock:global'", waitTime = 5, leaseTime = 10, timeUnit = TimeUnit.SECONDS)
    public WarehouseVo add(Warehouse warehouse) {
        warehouse.setWarehouseCode(generateWarehouseCode());
        if (warehouse.getStatus() == null) {
            warehouse.setStatus(1);
        }
        warehouseMapper.insert(warehouse);
        return getDetailById(warehouse.getId());
    }

    /**
     * 生成仓库编码
     * @return 仓库编码
     */
    private String generateWarehouseCode() {
        Long seq = stringRedisTemplate.opsForValue().increment("warehouse:code");
        return "WH" + String.format("%06d", seq);
    }

    /**
     * 批量更新仓库状态（乐观锁实现）
     * @param dto 批量更新仓库状态参数DTO
     * @return 失败的仓库信息：key=仓库ID，value=失败原因；空 map 表示全部成功
     */
    @Override
    public Map<String, String> updateBatchStatus(WarehouseBatchStatusDto dto) {
        Map<String, String> failures = new LinkedHashMap<>();
        // 批量更新仓库状态，使用乐观锁实现
        for (String warehouseId : dto.getWarehouseIds()) {
            Integer expectedVersion = dto.getVersionByWarehouseId().get(warehouseId);
            if (expectedVersion == null) {
                failures.put(warehouseId, "未找到版本号");
                continue;
            }
            Long id = Long.parseLong(warehouseId);
            Warehouse warehouse = new Warehouse();
            warehouse.setId(id);
            warehouse.setStatus(dto.getStatus());
            warehouse.setVersion(expectedVersion);
            int rows = warehouseMapper.updateById(warehouse);
            if (rows == 0) {
                failures.put(warehouseId, "仓库不存在或数据已发生变化，请刷新后重试");
            }
        }

        return failures;
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