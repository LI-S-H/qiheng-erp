# 系统与权限数据库结构

依据 2026-11-21 提供的 MySQL 结构导出整理，列类型、默认值和索引以建表文件为准；业务含义及关联仍需与代码逐项核实。

## `sys_dept`

表用途：部门表

| 字段 | 类型 | 允许 NULL | 默认值 / 自动生成 | 字段说明 |
|---|---|---|---|---|
| `id` | `bigint` | 否 | — | 部门ID |
| `parent_id` | `bigint` | 否 | '0' | 上级部门ID，顶级为0 |
| `ancestors` | `varchar(500)` | 否 | '' | 祖级路径 |
| `dept_name` | `varchar(100)` | 否 | — | 部门名称 |
| `status` | `tinyint` | 否 | '1' | 状态：1启用，0禁用 |
| `create_time` | `datetime` | 否 | CURRENT_TIMESTAMP | 创建时间 |
| `update_time` | `datetime` | 否 | CURRENT_TIMESTAMP / ON UPDATE CURRENT_TIMESTAMP | 更新时间 |
| `deleted` | `tinyint` | 否 | '0' | 逻辑删除：0正常，1删除 |
| `version` | `int` | 否 | '0' | 乐观锁版本号 |

索引与约束：

- ``PRIMARY KEY (`id`)``
- ``UNIQUE KEY `sys_dept_pk` (`dept_name`)``
- ``KEY `idx_sys_dept_parent` (`parent_id`)``
- ``KEY `idx_sys_dept_deleted_status` (`deleted`,`status`)``

## `sys_permission`

表用途：权限码目录表

| 字段 | 类型 | 允许 NULL | 默认值 / 自动生成 | 字段说明 |
|---|---|---|---|---|
| `id` | `bigint` | 否 | — | 权限ID |
| `permission_code` | `varchar(100)` | 否 | — | 权限码，例如 system:user:query |
| `permission_name` | `varchar(100)` | 否 | — | 权限名称 |
| `module_code` | `varchar(64)` | 否 | — | 所属模块编码 |
| `action_type` | `varchar(32)` | 否 | — | 操作类型：query/create/update/delete/manage/execute |
| `status` | `tinyint` | 否 | '1' | 状态：1启用，0禁用 |
| `sort_order` | `int` | 否 | '0' | 排序值，越小越靠前 |
| `description` | `varchar(500)` | 否 | '' | 权限说明 |
| `create_time` | `datetime` | 否 | CURRENT_TIMESTAMP | 创建时间 |
| `update_time` | `datetime` | 否 | CURRENT_TIMESTAMP / ON UPDATE CURRENT_TIMESTAMP | 更新时间 |
| `deleted` | `tinyint` | 否 | '0' | 逻辑删除：0正常，1删除 |
| `version` | `int` | 否 | '0' | 乐观锁版本号 |

索引与约束：

- ``PRIMARY KEY (`id`)``
- ``UNIQUE KEY `uk_sys_permission_code` (`permission_code`)``
- ``KEY `idx_sys_permission_module_action` (`module_code`,`action_type`)``
- ``KEY `idx_sys_permission_deleted_status_sort` (`deleted`,`status`,`sort_order`)``

## `sys_role`

表用途：角色表

| 字段 | 类型 | 允许 NULL | 默认值 / 自动生成 | 字段说明 |
|---|---|---|---|---|
| `id` | `bigint` | 否 | — | 角色ID |
| `role_code` | `varchar(64)` | 否 | — | 角色编码 |
| `role_name` | `varchar(100)` | 否 | — | 角色名称 |
| `permission_codes` | `json` | 否 | — | 粗粒度权限码列表 |
| `status` | `tinyint` | 否 | '1' | 状态：1启用，0禁用 |
| `create_time` | `datetime` | 否 | CURRENT_TIMESTAMP | 创建时间 |
| `update_time` | `datetime` | 否 | CURRENT_TIMESTAMP / ON UPDATE CURRENT_TIMESTAMP | 更新时间 |
| `deleted` | `tinyint` | 否 | '0' | 逻辑删除：0正常，1删除 |
| `remark` | `varchar(500)` | 否 | '' | 备注 |
| `version` | `int` | 否 | '0' | 乐观锁版本号 |

索引与约束：

- ``PRIMARY KEY (`id`)``
- ``UNIQUE KEY `uk_sys_role_code` (`role_code`)``
- ``KEY `idx_sys_role_deleted_status` (`deleted`,`status`)``

## `sys_user`

表用途：用户表

| 字段 | 类型 | 允许 NULL | 默认值 / 自动生成 | 字段说明 |
|---|---|---|---|---|
| `id` | `bigint` | 否 | — | 用户ID |
| `username` | `varchar(64)` | 否 | — | 登录账号 |
| `password_hash` | `varchar(255)` | 否 | — | 密码哈希 |
| `real_name` | `varchar(100)` | 否 | '' | 用户姓名 |
| `dept_id` | `bigint` | 否 | — | 所属部门ID |
| `is_admin` | `tinyint` | 否 | '0' | 是否超级管理员：1是，0否 |
| `status` | `tinyint` | 否 | '1' | 状态：1启用，0禁用 |
| `last_login_at` | `datetime` | 是 | NULL | 最近登录时间 |
| `create_time` | `datetime` | 否 | CURRENT_TIMESTAMP | 创建时间 |
| `update_time` | `datetime` | 否 | CURRENT_TIMESTAMP / ON UPDATE CURRENT_TIMESTAMP | 更新时间 |
| `deleted` | `tinyint` | 否 | '0' | 逻辑删除：0正常，1删除 |
| `version` | `int` | 否 | '0' | 乐观锁版本号 |

索引与约束：

- ``PRIMARY KEY (`id`)``
- ``UNIQUE KEY `uk_sys_user_username` (`username`)``
- ``UNIQUE KEY `sys_user_pk` (`username`)``
- ``KEY `idx_sys_user_dept` (`dept_id`)``
- ``KEY `idx_sys_user_deleted_status` (`deleted`,`status`)``

## `sys_user_role`

表用途：用户角色关系表

| 字段 | 类型 | 允许 NULL | 默认值 / 自动生成 | 字段说明 |
|---|---|---|---|---|
| `id` | `bigint` | 否 | — | 关系ID |
| `user_id` | `bigint` | 否 | — | 用户ID |
| `role_id` | `bigint` | 否 | — | 角色ID |
| `create_time` | `datetime` | 否 | CURRENT_TIMESTAMP | 创建时间 |
| `update_time` | `datetime` | 否 | CURRENT_TIMESTAMP / ON UPDATE CURRENT_TIMESTAMP | 更新时间 |

索引与约束：

- ``PRIMARY KEY (`id`)``
- ``UNIQUE KEY `uk_sys_user_role` (`user_id`,`role_id`)``
- ``KEY `idx_sys_user_role_role` (`role_id`)``

## `system_exception`

表用途：系统异常记录表

| 字段 | 类型 | 允许 NULL | 默认值 / 自动生成 | 字段说明 |
|---|---|---|---|---|
| `id` | `bigint` | 否 | — | 系统异常记录ID |
| `event_id` | `varchar(20) COLLATE ascii_bin` | 否 | — | 异常事件雪花ID，同一事件重投保持不变 |
| `exception_no` | `varchar(64)` | 否 | — | 稳定异常编号，例如 AI-MCP-20260701-001 |
| `exception_type` | `varchar(64)` | 否 | — | 异常类型：MCP_TOOL_FAILED、MQ_DEAD_LETTER、EXT_CALLBACK_FAILED、JOB_FAILED、COMPENSATION_FAILED |
| `source_module` | `varchar(64)` | 否 | — | 来源模块：AI、MQ、LOGISTICS、SCHEDULER等 |
| `source_no` | `varchar(100)` | 是 | NULL | 来源业务单号、消息ID、任务编码或第三方回调幂等键 |
| `severity` | `varchar(16)` | 否 | 'MEDIUM' | 严重级别：HIGH、MEDIUM、LOW |
| `error_code` | `varchar(100)` | 是 | NULL | 错误码 |
| `error_message` | `varchar(1000)` | 否 | — | 错误信息 |
| `detail_summary` | `varchar(1000)` | 否 | — | 工作台可展示的问题摘要 |
| `resolve_hint` | `varchar(1000)` | 是 | NULL | 处理建议 |
| `status` | `varchar(32)` | 否 | 'PENDING' | 状态：PENDING、PROCESSING、RESOLVED、IGNORED |
| `occurred_at` | `datetime` | 否 | — | 异常发生时间 |
| `last_retry_at` | `datetime` | 是 | NULL | 最近重试时间 |
| `handled_by` | `bigint` | 是 | NULL | 处理人ID |
| `handled_at` | `datetime` | 是 | NULL | 处理时间 |
| `handle_remark` | `varchar(500)` | 是 | NULL | 处理备注 |
| `create_time` | `datetime` | 否 | CURRENT_TIMESTAMP | 创建时间 |
| `update_time` | `datetime` | 否 | CURRENT_TIMESTAMP / ON UPDATE CURRENT_TIMESTAMP | 更新时间 |

索引与约束：

- ``PRIMARY KEY (`id`)``
- ``UNIQUE KEY `uk_system_exception_event_id` (`event_id`)``
- ``UNIQUE KEY `uk_system_exception_no` (`exception_no`)``
- ``KEY `idx_system_exception_status_severity` (`status`,`severity`,`occurred_at`)``
- ``KEY `idx_system_exception_type_status` (`exception_type`,`status`)``
- ``KEY `idx_system_exception_source` (`source_module`,`source_no`)``
