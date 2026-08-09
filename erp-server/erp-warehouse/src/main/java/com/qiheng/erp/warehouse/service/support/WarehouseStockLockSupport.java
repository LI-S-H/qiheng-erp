package com.qiheng.erp.warehouse.service.support;

import com.qiheng.erp.common.exception.BizException;
import com.qiheng.erp.common.exception.ErrorCode;
import com.qiheng.erp.warehouse.domain.warehousestock.entity.WarehouseStock;
import com.qiheng.erp.warehouse.mapper.WarehouseStockMapper;
import org.springframework.stereotype.Component;

import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 按仓库和产品固定顺序锁定库存余额。 */
@Component
public class WarehouseStockLockSupport {

    private final WarehouseStockMapper warehouseStockMapper;

    public WarehouseStockLockSupport(WarehouseStockMapper warehouseStockMapper) {
        this.warehouseStockMapper = warehouseStockMapper;
    }

    /**
     * 锁定同一仓库下已有的库存记录。调用方必须处于外层业务事务中。
     * 不存在的库存记录不在结果中，入库场景仍由调用方按既有逻辑创建。
     */
    public Map<Long, WarehouseStock> lockExistingStocks(Long warehouseId, Collection<Long> productIds) {
        List<Long> sortedProductIds = productIds.stream()
                .filter(productId -> productId != null)
                .distinct()
                .sorted()
                .toList();
        if (sortedProductIds.isEmpty()) {
            return Map.of();
        }
        try {
            return warehouseStockMapper.selectByWarehouseAndProductIdsForUpdate(warehouseId, sortedProductIds).stream()
                    .collect(Collectors.toMap(WarehouseStock::getProductId, Function.identity()));
        } catch (RuntimeException e) {
            if (isLockWaitTimeout(e)) {
                throw new BizException(ErrorCode.OPERATION_FAILED.getCode(),
                        "库存正在被其他单据处理，请稍后重试");
            }
            throw e;
        }
    }

    /**
     * MyBatis/Spring 对 JDBC 超时的包装类型随驱动版本不同而变化，
     * 因此以 JDBC 标准超时类型或 MySQL 1205 错误码识别，不把死锁 1213 误判为可等待冲突。
     */
    private boolean isLockWaitTimeout(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof SQLTimeoutException) {
                return true;
            }
            if (current instanceof SQLException sqlException && sqlException.getErrorCode() == 1205) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
