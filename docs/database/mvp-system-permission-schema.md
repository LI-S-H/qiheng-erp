# MVP 系统权限库表设计：极简版

## 设计目标

本设计用于第一版 ERP MVP，优先保证系统能尽快开发出来。权限先做粗粒度控制：页面可以统一展示，用户点击进入或查询大表数据时，再根据当前用户角色里的权限码判断是否允许访问。

## 简化原则

- 暂不设计菜单权限表，不维护前端路由、组件路径、图标、显示隐藏等字段。
- 暂不设计按钮级权限，先控制模块入口和核心大表查询权限。
- 暂不设计独立权限表，权限码直接保存在 `sys_role.permission_codes`。
- 暂不设计部门级、仓库级、自定义数据范围、字段级权限，后续功能稳定后再补。
- MVP 系统权限模块只保留 4 张表：`sys_dept`、`sys_user`、`sys_role`、`sys_user_role`。

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

## 表：sys_user（用户表）

| 字段 | 类型 | 说明 |
|---|---|---|
| id | bigint PK | 用户ID |
| username | varchar(64) | 登录账号，唯一 |
| password_hash | varchar(255) | 密码哈希 |
| real_name | varchar(100) | 用户姓名 |
| dept_id | bigint | 所属部门ID |
| is_admin | tinyint | 是否超级管理员 |
| status | tinyint | 状态 |
| last_login_at | datetime | 最近登录时间 |
| created_at | datetime | 创建时间 |
| updated_at | datetime | 更新时间 |
| deleted | tinyint | 逻辑删除 |

关系说明：用户通过 `sys_user_role` 绑定角色。超级管理员 `is_admin = 1` 默认拥有全部权限。

## 表：sys_role（角色表）

| 字段 | 类型 | 说明 |
|---|---|---|
| id | bigint PK | 角色ID |
| role_code | varchar(64) | 角色编码，唯一 |
| role_name | varchar(100) | 角色名称 |
| permission_codes | json | 粗粒度权限码列表 |
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

关系说明：普通用户的权限来自所有角色 `permission_codes` 的并集。MVP 阶段先判断模块和大表查询权限，不做按钮级、字段级权限。

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

## MVP 权限规则

- 页面菜单先统一展示，不从数据库动态生成。
- 用户进入页面或调用接口时校验权限码。
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

接口或 AI Tool 鉴权时，先由 Sa-Token 根据 token 找到 Redis session，再读取当前用户和权限上下文。权限码来源仍以数据库角色为准，`sys_role.permission_codes` 是最终授权配置；Redis session 只是登录态和权限上下文缓存。

用户禁用、重置密码、角色变更或权限码变更后，需要主动清理对应用户 session，或让权限上下文重新加载，避免旧权限继续生效。这样比 JWT 更适合 ERP 场景，因为服务端可以立即踢人下线、撤销权限和控制敏感 AI Tool 调用。

## 测试场景

- 用户可以登录并获取角色、权限码、部门信息。
- 无权限用户可以看到页面入口，但进入页面或查询接口会被拒绝。
- 有 `purchase:query` 的用户可以查询采购大表，没有该权限则不能查询。
- AI 查询库存时必须具备 `ai:query:stock`。
- 粗粒度权限通过后，接口可以查询对应模块数据。
