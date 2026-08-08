-- MVP 系统权限库表设计：极简版
-- 数据库：MySQL 8
-- 说明：主键由 MyBatis-Plus ASSIGN_ID 生成，因此不使用 AUTO_INCREMENT。

CREATE DATABASE IF NOT EXISTS erp DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
USE erp;

CREATE TABLE IF NOT EXISTS sys_dept (
    id BIGINT NOT NULL COMMENT '部门ID',
    parent_id BIGINT NOT NULL DEFAULT 0 COMMENT '上级部门ID，顶级为0',
    ancestors VARCHAR(500) NOT NULL DEFAULT '' COMMENT '祖级路径',
    dept_name VARCHAR(100) NOT NULL COMMENT '部门名称',
    status TINYINT NOT NULL DEFAULT 1 COMMENT '状态：1启用，0禁用',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常，1删除',
    version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    PRIMARY KEY (id),
    KEY idx_sys_dept_parent (parent_id),
    KEY idx_sys_dept_deleted_status (deleted, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='部门表';

CREATE TABLE IF NOT EXISTS sys_user (
    id BIGINT NOT NULL COMMENT '用户ID',
    username VARCHAR(64) NOT NULL COMMENT '登录账号',
    password_hash VARCHAR(255) NOT NULL COMMENT '密码哈希',
    real_name VARCHAR(100) NOT NULL DEFAULT '' COMMENT '用户姓名',
    dept_id BIGINT NOT NULL COMMENT '所属部门ID',
    is_admin TINYINT NOT NULL DEFAULT 0 COMMENT '是否超级管理员：1是，0否',
    status TINYINT NOT NULL DEFAULT 1 COMMENT '状态：1启用，0禁用',
    last_login_at DATETIME DEFAULT NULL COMMENT '最近登录时间',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常，1删除',
    version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sys_user_username (username),
    KEY idx_sys_user_dept (dept_id),
    KEY idx_sys_user_deleted_status (deleted, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户表';

CREATE TABLE IF NOT EXISTS sys_role (
    id BIGINT NOT NULL COMMENT '角色ID',
    role_code VARCHAR(64) NOT NULL COMMENT '角色编码',
    role_name VARCHAR(100) NOT NULL COMMENT '角色名称',
    permission_codes JSON NOT NULL COMMENT '粗粒度权限码列表',
    status TINYINT NOT NULL DEFAULT 1 COMMENT '状态：1启用，0禁用',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常，1删除',
    remark VARCHAR(500) NOT NULL DEFAULT '' COMMENT '备注',
    version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sys_role_code (role_code),
    KEY idx_sys_role_deleted_status (deleted, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='角色表';

CREATE TABLE IF NOT EXISTS sys_permission (
    id BIGINT NOT NULL COMMENT '权限ID',
    permission_code VARCHAR(100) NOT NULL COMMENT '权限码，例如 system:user:query',
    permission_name VARCHAR(100) NOT NULL COMMENT '权限名称',
    module_code VARCHAR(64) NOT NULL COMMENT '所属模块编码',
    action_type VARCHAR(32) NOT NULL COMMENT '操作类型：query/create/update/delete/manage/execute',
    status TINYINT NOT NULL DEFAULT 1 COMMENT '状态：1启用，0禁用',
    sort_order INT NOT NULL DEFAULT 0 COMMENT '排序值，越小越靠前',
    description VARCHAR(500) NOT NULL DEFAULT '' COMMENT '权限说明',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常，1删除',
    version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sys_permission_code (permission_code),
    KEY idx_sys_permission_module_action (module_code, action_type),
    KEY idx_sys_permission_deleted_status_sort (deleted, status, sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='权限码目录表';

CREATE TABLE IF NOT EXISTS sys_user_role (
    id BIGINT NOT NULL COMMENT '关系ID',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    role_id BIGINT NOT NULL COMMENT '角色ID',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sys_user_role (user_id, role_id),
    KEY idx_sys_user_role_role (role_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户角色关系表';

-- ============================================================
-- 系统模块种子数据（以 qiheng-2026_07_17_02_57_22-dump.sql 为准）
-- 已存在的业务基线使用 INSERT IGNORE：只补齐空库，不覆盖现有 MySQL 数据。
-- 请勿再执行 seed_system_permission.sql；该文件仅保留迁移说明。
-- ============================================================

INSERT IGNORE INTO sys_dept
  (id, parent_id, ancestors, dept_name, status, create_time, update_time, deleted, version)
VALUES
  (1900000000000000100, 0, '0', '行政部', 1, '2026-06-05 20:10:00', '2026-06-08 09:10:00', 0, 0),
  (1900000000000000102, 0, '0', '采购部', 1, '2026-06-05 20:13:00', '2026-06-23 07:03:59', 0, 0),
  (1900000000000000103, 0, '0', '销售部', 1, '2026-06-05 20:14:00', '2026-06-23 07:03:59', 0, 0),
  (1900000000000000104, 0, '0', '仓储部', 1, '2026-06-05 20:15:00', '2026-07-03 17:01:09', 0, 0),
  (1900000000000000106, 1900000000000000102, '0,1900000000000000102', '供应商维护组', 1, '2026-06-07 09:30:00', '2026-06-23 07:03:59', 0, 0),
  (1900000000000000107, 1900000000000000102, '0,1900000000000000102', '采购跟单组', 1, '2026-06-07 09:31:00', '2026-06-23 07:03:59', 0, 0),
  (1900000000000000108, 1900000000000000103, '0,1900000000000000103', '华东销售组', 0, '2026-06-07 09:32:00', '2026-06-23 06:51:22', 0, 0),
  (1900000000000000109, 1900000000000000103, '0,1900000000000000103', '华南销售组', 1, '2026-06-07 09:33:00', '2026-06-23 07:03:59', 0, 0),
  (1900000000000000110, 1900000000000000104, '0,1900000000000000104', '入库作业组', 1, '2026-06-07 09:34:00', '2026-07-03 17:01:09', 0, 0),
  (1900000000000000111, 1900000000000000104, '0,1900000000000000104', '出库复核组', 1, '2026-06-07 09:35:00', '2026-07-03 17:01:09', 0, 0);

INSERT IGNORE INTO sys_permission
  (id, permission_code, permission_name, module_code, action_type, status, sort_order, description, create_time, update_time, deleted, version)
VALUES
  (1900000000000002001, 'system:user:query', '用户查询', 'system', 'query', 1, 10, '查看用户账号及其部门、角色信息', '2026-06-08 10:00:00', '2026-06-10 09:30:00', 0, 0),
  (1900000000000002002, 'system:user:manage', '用户维护', 'system', 'manage', 1, 20, '新增、编辑、启停和删除用户账号', '2026-06-08 10:00:00', '2026-06-10 09:30:00', 0, 0),
  (1900000000000002020, 'system:dept:manage', '部门维护', 'system', 'manage', 1, 25, '新增、编辑、启停和删除部门', '2026-06-08 10:00:00', '2026-06-10 09:30:00', 0, 0),
  (1900000000000002003, 'system:role:query', '角色查询', 'system', 'query', 1, 30, '查看角色及权限绑定信息', '2026-06-08 10:00:00', '2026-06-10 09:30:00', 0, 0),
  (1900000000000002004, 'system:role:manage', '角色维护', 'system', 'manage', 1, 40, '维护角色和角色权限', '2026-06-08 10:00:00', '2026-06-10 09:30:00', 0, 0),
  (1900000000000002021, 'system:permission:query', '权限码查询', 'system', 'query', 1, 45, '查看权限码目录、权限码详情和可授权权限码选项', '2026-06-26 13:08:01', '2026-06-26 13:08:01', 0, 0),
  (1900000000000002022, 'system:permission:create', '权限码新增', 'system', 'create', 1, 46, '新增权限码目录项', '2026-06-26 13:08:01', '2026-06-26 13:08:01', 0, 0),
  (1900000000000002023, 'system:permission:manage', '权限码维护', 'system', 'manage', 1, 47, '编辑、启停、删除和批量维护权限码目录项', '2026-06-26 13:08:01', '2026-06-26 13:08:01', 0, 0),
  (1900000000000002005, 'product:query', '产品查询', 'product', 'query', 1, 50, '查看产品与分类信息', '2026-06-08 10:00:00', '2026-06-10 09:30:00', 0, 0),
  (1900000000000002006, 'product:manage', '产品维护', 'product', 'manage', 1, 60, '维护产品与分类信息', '2026-06-08 10:00:00', '2026-06-10 09:30:00', 0, 0),
  (1900000000000002007, 'warehouse:query', '仓库与库存查询', 'warehouse', 'query', 1, 70, '查看仓库信息、入库单、出库单和库存流水', '2026-06-08 10:00:00', '2026-07-16 17:13:07', 0, 0),
  (1900000000000002008, 'warehouse:manage', '仓库与库存维护', 'warehouse', 'manage', 1, 80, '维护仓库信息、执行出入库操作和库存调整', '2026-06-08 10:00:00', '2026-07-16 17:13:07', 0, 0),
  (1900000000000002009, 'supplier:query', '供应商查询', 'supplier', 'query', 1, 90, '查看供应商及供货产品', '2026-06-08 10:00:00', '2026-06-10 09:30:00', 0, 0),
  (1900000000000002040, 'supplier:create', '供应商创建', 'supplier', 'create', 1, 91, '创建、编辑、启停和删除供应商及供货产品', '2026-07-16 10:00:00', '2026-07-16 10:00:00', 0, 0),
  (1900000000000002041, 'supplier:manage', '供应商管理', 'supplier', 'manage', 1, 92, '管理供应商及供货关系', '2026-07-16 10:00:00', '2026-07-16 10:00:00', 0, 0),
  (1900000000000002010, 'purchase:query', '采购查询', 'purchase', 'query', 1, 100, '查看采购订单', '2026-06-08 10:00:00', '2026-06-10 09:30:00', 0, 0),
  (1900000000000002050, 'purchase:manage', '采购管理', 'purchase', 'manage', 1, 105, '编辑、删除和审核采购订单', '2026-07-16 10:00:00', '2026-07-16 10:00:00', 0, 0),
  (1900000000000002011, 'purchase:create', '采购创建', 'purchase', 'create', 1, 110, '创建采购订单', '2026-06-08 10:00:00', '2026-06-10 09:30:00', 0, 0),
  (1900000000000002012, 'customer:query', '客户查询', 'customer', 'query', 1, 120, '查看客户资料', '2026-06-08 10:00:00', '2026-06-10 09:30:00', 0, 0),
  (1900000000000002060, 'customer:create', '客户创建', 'customer', 'create', 1, 121, '创建、编辑、启停和删除客户', '2026-07-16 10:00:00', '2026-07-16 10:00:00', 0, 0),
  (1900000000000002061, 'customer:manage', '客户管理', 'customer', 'manage', 1, 122, '管理客户资料', '2026-07-16 10:00:00', '2026-07-16 10:00:00', 0, 0),
  (1900000000000002013, 'sales:query', '销售查询', 'sales', 'query', 1, 130, '查看销售订单', '2026-06-08 10:00:00', '2026-06-10 09:30:00', 0, 0),
  (1900000000000002070, 'sales:manage', '销售管理', 'sales', 'manage', 1, 135, '编辑、删除和审核销售订单', '2026-07-16 10:00:00', '2026-07-16 10:00:00', 0, 0),
  (1900000000000002014, 'sales:create', '销售创建', 'sales', 'create', 1, 140, '创建销售订单', '2026-06-08 10:00:00', '2026-06-10 09:30:00', 0, 0),
  (1900000000000002081, 'return:query', '退货查询', 'return', 'query', 1, 145, '查看统一退货单', '2026-08-08 10:00:00', '2026-08-08 10:00:00', 0, 0),
  (1900000000000002015, 'ai:query:stock', '库存问答', 'ai', 'query', 1, 150, '使用库存知识问答', '2026-06-08 10:00:00', '2026-06-10 09:30:00', 0, 0),
  (1900000000000002016, 'ai:query:sales', '销售问答', 'ai', 'query', 1, 160, '使用销售经营问答', '2026-06-08 10:00:00', '2026-06-10 09:30:00', 0, 0),
  (1900000000000002017, 'ai:query:purchase', '采购问答', 'ai', 'query', 1, 170, '使用采购经营问答', '2026-06-08 10:00:00', '2026-06-10 09:30:00', 0, 0),
  (1900000000000002018, 'ai:ops:suggest', '运维建议', 'ai', 'execute', 1, 180, '获取系统运维建议', '2026-06-08 10:00:00', '2026-06-10 09:30:00', 0, 0),
  (1900000000000002019, 'ai:decision:suggest', '决策建议', 'ai', 'execute', 1, 190, '获取经营决策建议', '2026-06-08 10:00:00', '2026-06-26 20:00:25', 0, 0);

INSERT IGNORE INTO sys_role
  (id, role_code, role_name, permission_codes, status, create_time, update_time, deleted, remark, version)
VALUES
  (1900000000000001001, 'SUPER_ADMIN', '超级管理员', '["*"]', 1, '2026-06-05 20:30:00', '2026-06-08 09:12:30', 0, '拥有系统全部访问和维护权限', 0),
  (1900000000000001002, 'PURCHASE_STAFF', '采购员', '["product:query", "warehouse:query", "supplier:query", "supplier:create", "purchase:query", "purchase:create", "purchase:manage", "return:query"]', 1, '2026-06-05 20:35:00', '2026-07-16 10:00:00', 0, '负责供应商维护和采购订单操作', 0),
  (1900000000000001003, 'SALES_STAFF', '销售员', '["product:query", "warehouse:query", "customer:query", "customer:create", "sales:query", "sales:create", "sales:manage", "return:query"]', 1, '2026-06-05 20:36:00', '2026-07-16 10:00:00', 0, '负责客户维护和销售订单操作', 0),
  (1900000000000001004, 'WAREHOUSE_STAFF', '仓管员', '["product:query", "warehouse:query", "warehouse:manage", "return:query"]', 1, '2026-06-05 20:37:00', '2026-06-27 14:06:10', 0, '负责仓库管理、出入库和库存调整', 4),
  (1900000000000001005, 'BUSINESS_MANAGER', '业务主管', '["product:query", "product:manage", "warehouse:query", "warehouse:manage", "supplier:query", "supplier:create", "purchase:query", "purchase:create", "purchase:manage", "customer:query", "customer:create", "sales:query", "sales:create", "sales:manage", "return:query", "ai:query:stock", "ai:query:sales", "ai:query:purchase", "ai:ops:suggest"]', 1, '2026-06-05 20:38:00', '2026-07-16 10:00:00', 0, '查看全链路业务数据和AI分析建议', 0),
  (1900000000000001006, 'AI_ANALYST', 'AI分析师', '["product:query", "warehouse:query", "supplier:query", "purchase:query", "customer:query", "sales:query", "return:query", "ai:query:stock", "ai:query:sales", "ai:query:purchase", "ai:ops:suggest", "ai:decision:suggest"]', 1, '2026-06-05 20:39:00', '2026-07-03 17:00:43', 0, '仅查询业务数据和AI问答，当前停用', 0);

INSERT IGNORE INTO sys_user
  (id, username, password_hash, real_name, dept_id, is_admin, status, last_login_at, create_time, update_time, deleted, version)
VALUES
  (1900000000000000001, 'admin', '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iKTVKIUi', '系统管理员', 1900000000000000100, 1, 1, '2026-07-04 12:39:43', '2026-06-05 20:30:00', '2026-07-04 04:39:43', 0, 0),
  (1900000000000000002, 'purchase01', '$2a$10$CEh8QP443ZG/NLZ9PTiZruBb2IHA9M6ZG7y9Y1fQX34/BQ5oJq15G', '采购主管', 1900000000000000102, 0, 1, NULL, '2026-06-05 20:35:00', '2026-06-23 14:40:11', 0, 0),
  (1900000000000000003, 'sales01', '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iKTVKIUi', '销售主管', 1900000000000000103, 0, 1, NULL, '2026-06-05 20:36:00', '2026-06-23 14:20:16', 0, 0),
  (1900000000000000004, 'warehouse01', '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iKTVKIUi', '仓管主管', 1900000000000000104, 0, 1, NULL, '2026-06-05 20:37:00', '2026-06-07 17:24:11', 0, 0),
  (1900000000000000005, 'manager01', '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iKTVKIUi', '业务主管', 1900000000000000100, 0, 1, NULL, '2026-06-05 20:38:00', '2026-06-07 17:24:11', 0, 0),
  (1900000000000000006, 'sales_stop', '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iKTVKIUi', '已停用销售', 1900000000000000108, 0, 0, NULL, '2026-06-05 20:40:00', '2026-07-03 16:55:13', 0, 0);

INSERT IGNORE INTO sys_user_role (id, user_id, role_id, create_time, update_time) VALUES
  (1900000000000003001, 1900000000000000001, 1900000000000001001, '2026-06-05 20:30:00', '2026-06-05 20:30:00'),
  (1900000000000003002, 1900000000000000002, 1900000000000001002, '2026-06-05 20:35:00', '2026-06-05 20:35:00'),
  (1900000000000003003, 1900000000000000003, 1900000000000001003, '2026-06-05 20:36:00', '2026-06-05 20:36:00'),
  (1900000000000003004, 1900000000000000004, 1900000000000001004, '2026-06-05 20:37:00', '2026-06-05 20:37:00'),
  (1900000000000003005, 1900000000000000005, 1900000000000001005, '2026-06-05 20:38:00', '2026-06-05 20:38:00'),
  (2073091028123750402, 1900000000000000006, 1900000000000001003, '2026-07-03 17:06:31', '2026-07-03 17:06:31'),
  (2073091028123750403, 1900000000000000006, 1900000000000001002, '2026-07-03 17:06:31', '2026-07-03 17:06:31');
