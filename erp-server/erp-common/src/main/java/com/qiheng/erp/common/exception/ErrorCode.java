package com.qiheng.erp.common.exception;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 错误码枚举
 */
@Getter
@AllArgsConstructor
public enum ErrorCode {

    // 通用错误 1xxxx
    PARAM_ERROR(10001, "参数错误"),
    DATA_NOT_FOUND(10002, "数据不存在"),
    DATA_DUPLICATE(10003, "数据重复"),
    OPERATION_FAILED(10004, "操作失败"),
    STATUS_INVALID(10005, "状态不允许此操作"),

    // 认证错误 2xxxx
    UNAUTHORIZED(20001, "未登录或登录已过期"),
    FORBIDDEN(20002, "无权限访问"),
    TOKEN_INVALID(20003, "Token 无效"),

    // 系统模块 3xxxx
    USER_PASSWORD_ERROR(30001, "用户名或密码错误"),
    USER_DISABLED(30002, "用户已停用"),
    ROLE_IN_USE(30003, "角色正在使用中，无法删除"),
    DEPT_HAS_CHILDREN(30004, "部门存在子部门，无法删除"),
    DEPT_HAS_USERS(30005, "部门下存在用户，无法删除"),
    PERMISSION_CODE_DUPLICATE(30006, "权限码已存在"),
    USER_NOT_FOUND(30007, "用户不存在"),
    DEPT_NOT_FOUND(30008, "部门不存在"),
    PARENT_DEPT_DISABLED(30009, "上级部门已停用，不能启用当前部门"),
    ROLE_DISABLED(30010, "部分角色已停用或不存在，无法分配,刷新后重试"),
    PERMISSION_NOT_FOUND(30011, "权限码不存在"),
    PERMISSION_IN_USE(30012, "权限码正在使用中，无法删除"),

    // 产品模块 4xxxx
    CATEGORY_NAME_DUPLICATE(40001, "同级别下分类名称已存在"),
    CATEGORY_DISABLED(40002, "分类已停用"),
    CATEGORY_HAS_CHILDREN(40003, "分类存在子分类，无法删除"),
    CATEGORY_HAS_PRODUCTS(40004, "分类下存在产品，无法删除"),
    PRODUCT_CODE_DUPLICATE(40005, "产品编码已存在"),
    CHILD_DEPT_EXISTS(40006, "部门下存在子部门，无法删除"),
    CATEGORY_ERROR(40007, "分类错误"),
    CATEGORY_NOT_FOUND(40008, "分类不存在"),
    PARENT_NOT_FOUND(40009, "上级分类不存在"),
    PARENT_DISABLED(40010, "上级分类已停用"),
    CATEGORY_CYCLE_REFERENCE(40011, "分类存在循环引用"),

    // 仓库模块 5xxxx
    WAREHOUSE_CODE_DUPLICATE(50001, "仓库编码已存在"),
    WAREHOUSE_HAS_STOCK(50002, "仓库下存在库存，无法删除"),
    STOCK_INSUFFICIENT(50003, "库存不足"),
    BILL_STATUS_INVALID(50004, "单据状态不允许此操作"),

    // 采购模块 6xxxx
    SUPPLIER_CODE_DUPLICATE(60001, "供应商编码已存在"),

    // 销售模块 7xxxx
    CUSTOMER_CODE_DUPLICATE(70001, "客户编码已存在"),

    // 未知错误
    UNKNOWN(99999, "系统内部错误");

    private final int code;
    private final String message;
}