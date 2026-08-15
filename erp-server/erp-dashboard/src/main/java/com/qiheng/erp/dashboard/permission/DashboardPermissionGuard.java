package com.qiheng.erp.dashboard.permission;

import com.qiheng.erp.security.domain.dto.LoginUser;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * 工作台权限裁剪器。
 *
 * <p>根据当前用户的权限码集合判断哪些聚合点应该返回数据；
 * 无权模块的指标、待办、列表返回空数组或 0 值，不泄露无权业务数据。</p>
 *
 * <p>当前实现仅根据业务查询权限裁剪模块粒度的可见性；
 * 异常/人工修复类待办的"被指派用户/角色/部门"细化判断
 * 由后续异常中心模块补充，本接口预留扩展位置。</p>
 *
 * @author Li
 * @since 2026-08-15
 */
@Component
public class DashboardPermissionGuard {

    /** 超级管理员通配符 */
    private static final String ADMIN_WILDCARD = "*";

    /** 采购业务查询 */
    private static final String PERM_PURCHASE_QUERY = "purchase:query";

    /** 销售业务查询 */
    private static final String PERM_SALES_QUERY = "sales:query";

    /** 仓库与库存查询 */
    private static final String PERM_WAREHOUSE_QUERY = "warehouse:query";

    /** 供应商查询 */
    private static final String PERM_SUPPLIER_QUERY = "supplier:query";

    /** 产品查询 */
    private static final String PERM_PRODUCT_QUERY = "product:query";

    /** 客户查询 */
    private static final String PERM_CUSTOMER_QUERY = "customer:query";

    /**
     * 当前用户是否可看工作台经营概览。
     * 调用方按 {@code dashboard:overview:query} 权限做入口拦截，本方法用于内部聚合裁剪。
     */
    public boolean canViewOverview(LoginUser user) {
        return hasAny(user, PERM_PURCHASE_QUERY, PERM_SALES_QUERY, PERM_WAREHOUSE_QUERY,
                PERM_SUPPLIER_QUERY, PERM_PRODUCT_QUERY, PERM_CUSTOMER_QUERY);
    }

    /** 是否可看采购相关聚合(待审核、采购单阶段) */
    public boolean canViewPurchase(LoginUser user) {
        return hasPermission(user, PERM_PURCHASE_QUERY);
    }

    /** 是否可看销售相关聚合(待审核、销售单阶段、商品排行) */
    public boolean canViewSales(LoginUser user) {
        return hasPermission(user, PERM_SALES_QUERY);
    }

    /** 是否可看仓库与库存聚合(待入库、待出库、库存风险) */
    public boolean canViewWarehouse(LoginUser user) {
        return hasPermission(user, PERM_WAREHOUSE_QUERY);
    }

    /** 是否可看供应商履约 */
    public boolean canViewSupplier(LoginUser user) {
        return hasPermission(user, PERM_SUPPLIER_QUERY);
    }

    /** 是否可看客户信用复核 */
    public boolean canViewCustomer(LoginUser user) {
        return hasPermission(user, PERM_CUSTOMER_QUERY);
    }

    /**
     * 判断是否拥有指定权限码之一，超级管理员视为拥有全部
     */
    private boolean hasAny(LoginUser user, String... codes) {
        if (user == null) {
            return false;
        }
        List<String> permissions = user.getPermissionCodes();
        if (permissions == null || permissions.isEmpty()) {
            return false;
        }
        if (permissions.contains(ADMIN_WILDCARD)) {
            return true;
        }
        Set<String> permissionSet = Set.copyOf(permissions);
        for (String code : codes) {
            if (permissionSet.contains(code)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断是否拥有指定权限码
     */
    private boolean hasPermission(LoginUser user, String code) {
        return hasAny(user, code);
    }
}