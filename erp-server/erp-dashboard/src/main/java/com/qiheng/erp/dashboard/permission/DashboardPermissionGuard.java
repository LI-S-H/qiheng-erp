package com.qiheng.erp.dashboard.permission;

import com.qiheng.erp.security.domain.dto.LoginUser;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * 工作台权限裁剪器。
 *
 * <p>根据当前用户的权限码集合判断哪些聚合点应该返回数据；
 * 无权模块不加载业务数据；指标和面板的展示状态由概览接口的 access 字段明确传达。</p>
 * <p>异常/人工修复类待办的"被指派用户/角色/部门"细化判断由后续异常中心模块补充，
 * 本类仅按业务模块 query / manage 权限裁剪；{@code dashboard:exception:query}
 * 是工作台内部权限码，用于独立控制系统异常聚合的可见性。</p>
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

    /** 采购业务执行（审批、管理） */
    private static final String PERM_PURCHASE_MANAGE = "purchase:manage";

    /** 销售业务执行（审批、管理） */
    private static final String PERM_SALES_MANAGE = "sales:manage";

    /** 仓库与库存执行（确认出入库等） */
    private static final String PERM_WAREHOUSE_MANAGE = "warehouse:manage";

    /** 工作台系统异常摘要 */
    private static final String PERM_DASHBOARD_EXCEPTION_QUERY = "dashboard:exception:query";

    /**
     * 是否可看采购相关聚合(待审核、采购单阶段)
     * @param user 当前登录用户
     * @return 是否拥有采购模块查询权限
     */
    public boolean canViewPurchase(LoginUser user) {
        return has(user, PERM_PURCHASE_QUERY);
    }

    /**
     * 是否可看销售相关聚合(待审核、销售单阶段、商品排行)
     * @param user 当前登录用户
     * @return 是否拥有销售模块查询权限
     */
    public boolean canViewSales(LoginUser user) {
        return has(user, PERM_SALES_QUERY);
    }

    /**
     * 是否可看仓库与库存聚合(待入库、待出库、库存风险)
     * @param user 当前登录用户
     * @return 是否拥有仓库模块查询权限
     */
    public boolean canViewWarehouse(LoginUser user) {
        return has(user, PERM_WAREHOUSE_QUERY);
    }

    /**
     * 是否可看供应商履约
     * @param user 当前登录用户
     * @return 是否拥有供应商模块查询权限
     */
    public boolean canViewSupplier(LoginUser user) {
        return has(user, PERM_SUPPLIER_QUERY);
    }

    /**
     * 是否可看经营趋势（销售曲线、采购曲线、毛利曲线任一即可）
     * @param user 当前登录用户
     * @return 是否同时缺失销售与采购查询权限
     */
    public boolean canViewTrend(LoginUser user) {
        return has(user, PERM_SALES_QUERY) || has(user, PERM_PURCHASE_QUERY);
    }

    /**
     * 是否可对采购待办进行"审核"操作（用于业务待办-采购类的细分校验）
     * @param user 当前登录用户
     * @return 是否拥有采购 manage 权限
     */
    public boolean canManagePurchase(LoginUser user) {
        return has(user, PERM_PURCHASE_MANAGE);
    }

    /**
     * 是否可对销售待办进行"审核"操作
     * @param user 当前登录用户
     * @return 是否拥有销售 manage 权限
     */
    public boolean canManageSales(LoginUser user) {
        return has(user, PERM_SALES_MANAGE);
    }

    /**
     * 是否可对仓库出入库待办进行"确认"操作
     * @param user 当前登录用户
     * @return 是否拥有仓库 manage 权限
     */
    public boolean canManageWarehouse(LoginUser user) {
        return has(user, PERM_WAREHOUSE_MANAGE);
    }

    /**
     * 是否可看工作台系统异常聚合
     * @param user 当前登录用户
     * @return 是否拥有 dashboard:exception:query 权限
     */
    public boolean canViewSystemException(LoginUser user) {
        return has(user, PERM_DASHBOARD_EXCEPTION_QUERY);
    }

    /**
     * 构建当前用户权限码集合的不可变快照，供单次请求内多次复用。
     *
     * @param user 当前登录用户
     * @return 权限码不可变集合；user 为空或权限码为空时返回空集合
     */
    public Set<String> permissionSnapshot(LoginUser user) {
        if (user == null) {
            return Set.of();
        }
        List<String> codes = user.getPermissionCodes();
        if (codes == null || codes.isEmpty()) {
            return Set.of();
        }
        if (codes.contains(ADMIN_WILDCARD)) {
            return Set.of(ADMIN_WILDCARD);
        }
        return Set.copyOf(codes);
    }

    /**
     * 判断快照中是否拥有指定权限码；超级管理员视为拥有全部权限。
     *
     * @param user 当前登录用户
     * @param code 待判断的权限码
     * @return 是否拥有指定权限
     */
    private boolean has(LoginUser user, String code) {
        Set<String> snapshot = permissionSnapshot(user);
        if (snapshot.isEmpty()) {
            return false;
        }
        if (snapshot.contains(ADMIN_WILDCARD)) {
            return true;
        }
        return snapshot.contains(code);
    }
}
