-- MVP 系统权限库表设计：极简版
-- 数据库：MySQL 8
-- 说明：主键由 MyBatis-Plus ASSIGN_ID 生成，因此不使用 AUTO_INCREMENT。

CREATE TABLE IF NOT EXISTS sys_dept (
    id BIGINT NOT NULL COMMENT '部门ID',
    parent_id BIGINT NOT NULL DEFAULT 0 COMMENT '上级部门ID，顶级为0',
    ancestors VARCHAR(500) NOT NULL DEFAULT '' COMMENT '祖级路径',
    dept_name VARCHAR(100) NOT NULL COMMENT '部门名称',
    status TINYINT NOT NULL DEFAULT 1 COMMENT '状态：1启用，0禁用',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常，1删除',
    PRIMARY KEY (id),
    KEY idx_sys_dept_parent (parent_id),
    KEY idx_sys_dept_deleted_status (deleted, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='部门表';

CREATE TABLE IF NOT EXISTS sys_user (
    id BIGINT NOT NULL COMMENT '用户ID',
    username VARCHAR(64) NOT NULL COMMENT '登录账号',
    password_hash VARCHAR(255) NOT NULL COMMENT '密码哈希',
    real_name VARCHAR(100) NOT NULL DEFAULT '' COMMENT '用户姓名',
    dept_id BIGINT DEFAULT NULL COMMENT '所属部门ID',
    is_admin TINYINT NOT NULL DEFAULT 0 COMMENT '是否超级管理员：1是，0否',
    status TINYINT NOT NULL DEFAULT 1 COMMENT '状态：1启用，0禁用',
    last_login_at DATETIME DEFAULT NULL COMMENT '最近登录时间',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常，1删除',
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
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常，1删除',
    remark VARCHAR(500) NOT NULL DEFAULT '' COMMENT '备注',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sys_role_code (role_code),
    KEY idx_sys_role_deleted_status (deleted, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='角色表';

CREATE TABLE IF NOT EXISTS sys_user_role (
    id BIGINT NOT NULL COMMENT '关系ID',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    role_id BIGINT NOT NULL COMMENT '角色ID',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sys_user_role (user_id, role_id),
    KEY idx_sys_user_role_role (role_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户角色关系表';
