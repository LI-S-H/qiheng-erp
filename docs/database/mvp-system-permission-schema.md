# MVP 系统权限库表设计：极简版

## 设计目标

本设计用于第一版 ERP MVP，优先保证系统能尽快开发出来。权限目录独立维护，页面入口统一展示；用户进入页面、调用接口或执行核心写操作时，根据当前用户角色里的有效权限码判断是否允许访问。

## 简化原则

- 暂不设计菜单权限表，不维护前端路由、组件路径、图标、显示隐藏等字段。
- 暂不设计动态按钮资源表；核心查询和写操作可以直接使用接口权限码校验。
- 权限目录独立维护在 `sys_permission`，角色授权结果继续保存在 `sys_role.permission_codes`，避免增加高频关联表查询。
- 暂不设计部门级、仓库级、自定义数据范围、字段级权限，后续功能稳定后再补。
- MVP 系统权限模块保留 5 张表：`sys_dept`、`sys_user`、`sys_role`、`sys_permission`、`sys_user_role`。

## 全局约定

- 数据库：MySQL 8。
- 主键：统一使用 `bigint`，由 MyBatis-Plus `ASSIGN_ID` 生成。
- 删除策略：使用 `deleted tinyint` 逻辑删除，`0` 正常，`1` 删除。
- 状态字段：`status tinyint`，`1` 启用，`0` 禁用。
- 外键策略：MVP 不创建物理外键，关系由业务层和索引保证。
- 评分和百分率字段如后续加入，统一遵守 `database-design-conventions.md`：用 `int` 存放大 100 倍后的整数。
- `sys_user_role` 是关系表，不使用 `deleted`，删除关系时直接物理删除关系记录。

## 表：sys_dept（部门表）

| 字段 | 类型 | 说明 |
|---|---|---|
| id | bigint PK | 部门ID |
| parent_id | bigint | 上级部门ID，顶级为 0 |
| ancestors | varchar(500) | 祖级路径，后续扩展部门数据权限时使用 |
| dept_name | varchar(100) | 部门名称 |
| status | tinyint | 状态 |
| created_at | datetime | 创建时间 |
| updated_at | datetime | 更新时间 |
| deleted | tinyint | 逻辑删除 |

关系说明：`sys_user.dept_id` 关联本表。MVP 阶段部门只作为用户归属信息，不参与权限过滤。

部门业务约束：

- 上级部门不能设置为当前部门或当前部门的任意下级，避免形成循环层级。
- 停用父级部门时必须在同一事务内级联停用全部下级；启用下级前，全部上级必须已启用或包含在同一次批量启用请求中。
- 停用部门不会自动停用员工账号，但停用部门不能继续作为新增或调整员工归属。
- 存在下级部门或仍有未删除用户通过 `sys_user.dept_id` 归属时禁止删除，接口返回 `409 Conflict`。
- 编辑接口修改部门状态时必须复用状态接口的级联规则，不能成为绕过入口。

## 表：sys_user（用户表）

| 字段 | 类型 | 说明 |
|---|---|---|
| id | bigint PK | 用户ID |
| username | varchar(64) | 登录账号，唯一 |
| password_hash | varchar(255) | 密码哈希 |
| real_name | varchar(100) | 用户姓名 |
| dept_id | bigint NOT NULL | 所属部门ID，用户必须归属一个部门 |
| is_admin | tinyint | 是否超级管理员 |
| status | tinyint | 状态 |
| last_login_at | datetime | 最近登录时间 |
| created_at | datetime | 创建时间 |
| updated_at | datetime | 更新时间 |
| deleted | tinyint | 逻辑删除 |

关系说明：用户通过 `sys_user_role` 绑定角色。新增或编辑用户时至少绑定一个角色；超级管理员 `is_admin = 1` 默认拥有全部权限。

用户列表查询约束：

- 登录账号使用 `username` 参数，对应 `sys_user.username` 包含匹配。
- 用户姓名使用 `realName` 参数，对应 `sys_user.real_name` 包含匹配。
- 部门、角色和状态分别使用 `deptId`、`roleId`、`status` 精确筛选；多个条件按 AND 组合。
- 不使用跨账号、姓名和部门名称的通用 `keyword` 参数。

## 表：sys_role（角色表）

| 字段 | 类型 | 说明 |
|---|---|---|
| id | bigint PK | 角色ID |
| role_code | varchar(64) | 角色编码，唯一 |
| role_name | varchar(100) | 角色名称 |
| permission_codes | json | 角色已授权权限码列表 |
| status | tinyint | 状态 |
| created_at | datetime | 创建时间 |
| updated_at | datetime | 更新时间 |
| deleted | tinyint | 逻辑删除 |
| remark | varchar(500) | 备注 |

权限码示例：

```json
[
  "product:query",
  "warehouse:query",
  "supplier:query",
  "customer:query",
  "purchase:query",
  "purchase:create",
  "sales:query",
  "sales:create",
  "ai:query:stock",
  "ai:query:sales",
  "ai:query:purchase"
]
```

关系说明：普通用户的候选权限来自所有启用角色 `permission_codes` 的并集，再与 `sys_permission` 中启用且未删除的权限码取交集。MVP 不设计动态按钮资源和字段级权限。

## 表：sys_permission（权限码目录表）

| 字段 | 类型 | 说明 |
|---|---|---|
| id | bigint PK | 权限ID |
| permission_code | varchar(100) | 权限码，唯一，使用小写字母、数字和冒号分段 |
| permission_name | varchar(100) | 权限名称 |
| module_code | varchar(64) | 所属模块编码 |
| action_type | varchar(32) | 操作类型：query/create/update/delete/manage/execute |
| status | tinyint | 状态 |
| sort_order | int | 排序值，0-9999 |
| description | varchar(500) | 权限说明 |
| created_at | datetime | 创建时间 |
| updated_at | datetime | 更新时间 |
| deleted | tinyint | 逻辑删除 |

关系说明：`sys_permission.permission_code` 是可授权权限码的目录，`sys_role.permission_codes` 保存角色实际获得的权限码数组。由于角色字段为 JSON，MVP 不建立物理外键；新增或修改角色时必须校验权限码存在且已启用，删除权限码前必须检查是否仍被任一未删除角色引用。超级管理员使用保留值 `*`，不作为普通目录数据新增或编辑。

约束与查询说明：

- `permission_code` 创建后不可修改，并使用全局唯一索引；逻辑删除后也不允许复用旧权限码，避免历史审计和旧 Session 产生歧义。
- 常规列表查询使用 `(deleted, status, sort_order)` 和 `(module_code, action_type)` 索引。
- `roleCount` 通过 `JSON_CONTAINS(sys_role.permission_codes, JSON_QUOTE(permission_code))` 聚合；MVP 角色数量较少，不额外维护角色权限关系表。角色规模明显增长后再迁移到 `sys_role_permission`。
- `module_code`、`action_type` 和状态枚举由应用层与 OpenAPI 共同校验；数据库保留 `varchar` 以支持后续模块扩展。
- 权限列表使用 `permissionCode` 和 `permissionName` 分别查询 `permission_code`、`permission_name`，不使用跨字段 `keyword`；多个条件按 AND 组合。

## 表：sys_user_role（用户角色关系表）

| 字段 | 类型 | 说明 |
|---|---|---|
| id | bigint PK | 关系ID |
| user_id | bigint | 用户ID |
| role_id | bigint | 角色ID |
| created_at | datetime | 创建时间 |
| updated_at | datetime | 更新时间 |

关系说明：用户和角色多对多。建议唯一索引 `(user_id, role_id)`。

## 表间关系

- `sys_user.dept_id` -> `sys_dept.id`
- `sys_user_role.user_id` -> `sys_user.id`
- `sys_user_role.role_id` -> `sys_role.id`
- `sys_role.permission_codes` 中的普通权限码 -> `sys_permission.permission_code`（业务层校验，无物理外键）

## MVP 权限规则

- 页面菜单先统一展示，不从数据库动态生成。
- 用户进入页面或调用接口时校验权限码。
- 角色授权选项只读取 `sys_permission.status = 1 AND deleted = 0` 的权限码。
- 权限码停用后不再允许新授权，并从普通用户有效权限集合中排除；状态变化后必须清理受影响用户 session 或重新加载权限上下文。
- 大表查询优先校验粗粒度查询权限，例如：
  - 产品：`product:query`
  - 库存：`warehouse:query`
  - 供应商：`supplier:query`
  - 客户：`customer:query`
  - 采购：`purchase:query`
  - 销售：`sales:query`
- AI Tool 也先复用粗粒度权限码，例如 `ai:query:stock`、`ai:query:sales`、`ai:query:purchase`。
- 部门级、仓库级、本人数据范围这些细粒度数据权限暂不实现，后续业务稳定后再补。

## 登录态与 Session 规则

MVP 阶段使用 Sa-Token 原始 UUID token，不引入 JWT 插件。登录成功后，token 只作为客户端访问凭证，服务端登录态统一存入 Redis session。

Redis session 建议保存：

- `user_id`
- `username`
- `real_name`
- `dept_id`
- `is_admin`
- `role_codes`
- `permission_codes`
- 后续扩展的数据权限上下文，例如部门范围、仓库范围、字段权限标记

接口或 AI Tool 鉴权时，先由 Sa-Token 根据 token 找到 Redis session，再读取当前用户和权限上下文。`sys_permission` 定义有效权限目录，`sys_role.permission_codes` 保存角色授权结果；Redis session 只是登录态和权限上下文缓存。

用户禁用、重置密码、角色变更或权限码变更后，需要主动清理对应用户 session，或让权限上下文重新加载，避免旧权限继续生效。这样比 JWT 更适合 ERP 场景，因为服务端可以立即踢人下线、撤销权限和控制敏感 AI Tool 调用。

## 测试场景

- 用户可以登录并获取角色、权限码、部门信息。
- 无权限用户可以看到页面入口，但进入页面或查询接口会被拒绝。
- 有 `purchase:query` 的用户可以查询采购大表，没有该权限则不能查询。
- AI 查询库存时必须具备 `ai:query:stock`。
- 粗粒度权限通过后，接口可以查询对应模块数据。
