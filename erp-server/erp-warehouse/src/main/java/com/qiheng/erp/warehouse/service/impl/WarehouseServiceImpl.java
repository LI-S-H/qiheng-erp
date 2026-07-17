package com.qiheng.erp.warehouse.service.impl;

import cn.hutool.core.lang.Validator;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.qiheng.erp.common.exception.BizException;
import com.qiheng.erp.common.exception.ErrorCode;
import com.qiheng.erp.common.result.PageResult;
import com.qiheng.erp.warehouse.domain.dto.WarehouseBatchDeleteDto;
import com.qiheng.erp.warehouse.domain.dto.WarehouseBatchStatusDto;
import com.qiheng.erp.warehouse.domain.dto.WarehousePageDto;
import com.qiheng.erp.warehouse.domain.dto.WarehouseStatusDto;
import com.qiheng.erp.warehouse.domain.entity.Warehouse;
import com.qiheng.erp.warehouse.domain.vo.WarehouseVo;
import com.qiheng.erp.warehouse.mapper.WarehouseMapper;
import com.qiheng.erp.warehouse.service.IWarehouseService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    public WarehouseVo add(Warehouse warehouse) {
        warehouse.setWarehouseCode(generateWarehouseCode());
        if (warehouse.getStatus() == null) {
            warehouse.setStatus(1);
        }
        if (!Validator.isMobile(warehouse.getContactPhone())) {
            throw new BizException(ErrorCode.OPERATION_FAILED.getCode(), "联系电话格式不正确");
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
            // TODO: 如果是禁用操作（status == 0），补充前置校验：
            //   1. 库存模块：检查该仓库下是否存在库存（quantity > 0），有库存则禁止禁用
            //   2. 出入库单模块：检查是否存在该仓库的未完结出入库单据
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

    /**
     * 更新仓库状态（乐观锁实现）
     * @param warehouseId 仓库ID
     * @param dto 状态更新参数
     */
    @Override
    public void updateStatus(Long warehouseId, WarehouseStatusDto dto) {
        // TODO: 如果是禁用操作（status == 0），补充前置校验：
        //   1. 库存模块：检查该仓库下是否存在库存（quantity > 0），有库存则禁止禁用
        //   2. 出入库单模块：检查是否存在该仓库的未完结出入库单据
        Warehouse warehouse = new Warehouse();
        warehouse.setId(warehouseId);
        warehouse.setStatus(dto.getStatus());
        warehouse.setVersion(dto.getVersion());
        int rows = warehouseMapper.updateById(warehouse);
        if (rows == 0) {
            throw new BizException(
                    ErrorCode.OPERATION_FAILED.getCode(),
                    "仓库不存在或数据已发生变化，请刷新后重试");
        }
    }

    /**
     * 批量删除仓库（逻辑删除，最佳努力模式，乐观锁实现）
     * @param dto 批量删除参数
     * @return 失败的仓库信息：key=仓库ID，value=失败原因；空 map 表示全部成功
     */
    @Override
    public Map<String, String> batchDelete(WarehouseBatchDeleteDto dto) {
        Map<String, String> failures = new LinkedHashMap<>();
        for (String warehouseIdStr : dto.getWarehouseIds()) {
            Long warehouseId = Long.parseLong(warehouseIdStr);
            Integer expectedVersion = dto.getVersionByWarehouseId().get(warehouseIdStr);
            if (expectedVersion == null) {
                failures.put(warehouseIdStr, "未找到版本号");
                continue;
            }
            // TODO: 以下模块完成后，补充数据关联校验，有引用则禁止删除：
            //   1. 库存模块：检查仓库下是否存在库存（quantity > 0）
            //   2. 出入库单模块：检查是否存在该仓库的出入库单据，有则禁止删除（需要溯源）
            //   3. 库存流水模块：检查是否存在该仓库的库存流水记录，有则禁止删除（需要溯源）
            int rows = warehouseMapper.deleteByIdWithVersion(warehouseId, expectedVersion);
            if (rows == 0) {
                failures.put(warehouseIdStr, "仓库不存在或数据已发生变化，请刷新后重试");
            }
        }
        return failures;
    }

    /**
     * 仓库更新（乐观锁实现）
     * @param warehouse 仓库实体（需包含 id 和 version）
     * @return 仓库VO
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public WarehouseVo update(Warehouse warehouse) {
        warehouse.setWarehouseCode(null);
        if (warehouse.getVersion() == null) {
            throw new BizException(ErrorCode.OPERATION_FAILED.getCode(), "版本号不能为空");
        }
        if (!Validator.isMobile(warehouse.getContactPhone())) {
            throw new BizException(ErrorCode.OPERATION_FAILED.getCode(), "联系电话格式不正确");
        }
        int rows = warehouseMapper.updateById(warehouse);
        if (rows == 0) {
            throw new BizException(ErrorCode.OPERATION_FAILED.getCode(),
                    "仓库不存在或数据已发生变化，请刷新后重试");
        }
        // TODO: 仓库信息更新成功后，补充级联更新：
        //   1. 库存模块：同步更新库存表中该仓库的 warehouse_name 字段
        //   （采购/销售/流水等其他业务模块不级联更新，通过快照机制留存历史信息）
        return getDetailById(warehouse.getId());
    }

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