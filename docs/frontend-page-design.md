# 前端页面设计思路

## 1. 前端架构选择

前端软件名为 **启衡 ERP**。

前端采用 `Vue 3 + TypeScript + Vite + shadcn-vue + Tailwind CSS + Pinia + Vue Router + Axios`。

选择这套技术栈的原因：

- 项目是企业内部 ERP 管理系统，核心页面以表格、筛选、表单、弹窗、状态流转和操作确认为主；shadcn-vue 提供可控的无样式基础组件，便于统一视觉并避免组件库默认样式侵入业务页面
- Tailwind CSS 负责布局和局部样式，颜色、字体、圆角和间距通过全局 token 与共享页面类统一，不在各模块重复维护大段局部 CSS
- Vue 3 + TypeScript 能保证开发效率，同时让接口字段、登录态和业务 DTO 有明确类型约束
- Vite 启动和构建速度快，适合按模块逐步生成、逐步审核的开发节奏
- Pinia 用于保存登录态、当前用户和后续模块级缓存，状态结构清晰
- Axios 统一处理 `satoken` 请求头、响应错误和登录失效跳转，避免每个模块重复处理鉴权逻辑

## 2. 目录组织思路

前端工程放在 `erp-web` 目录下，按“基础能力 + 业务模块”组织：

```text
erp-web
├── src
│   ├── api                 Axios 实例和统一请求函数
│   ├── layouts             后台主布局
│   ├── router              路由和登录守卫
│   ├── shared              通用类型、常量、组件和工具
│   ├── modules             按业务域拆分页面、接口和状态
│   └── styles              全局样式
└── package.json
```

这样设计的好处是每个模块可以独立审核：页面、接口封装、类型定义和局部状态都放在自己的模块目录下；公共能力只沉淀到 `shared` 和 `api`，避免模块之间互相引用过深。

## 3. 整体页面风格

启衡 ERP 面向企业内部采购、销售、仓储、管理人员和系统管理员，页面风格以“稳定、清晰、可长时间使用”为主。

设计原则：

- 后台主布局采用左侧菜单 + 顶部用户区 + 主内容区，符合 ERP 用户的日常操作习惯
- 左侧导航和顶部状态栏使用同一组深色中性 token，内容区使用低对比浅灰背景，避免出现两个互不相关的视觉系统
- 全站使用中文系统字体栈，不依赖远程字体；页面业务正文、输入、下拉和表格主文字复用 shadcn `text-sm` 的 `14px` 基准，按钮文字统一为 `13px` 常规字重，避免操作文字压过数据内容
- 列表页的四项概览合并为单条摘要栏，筛选区和表格各保留一个容器，减少卡片堆叠和页面碎片感
- 页面优先使用列表页、筛选区、表单弹窗、详情抽屉、状态标签和确认弹窗，服务高频业务处理
- 中等宽度下筛选区自动切换为规整两列；筛选下拉使用 shadcn `Popover + Command` 组合并固定从触发框下方等宽展开；输入焦点仅显示柔和模糊辉光
- 分页底栏保持 `52px` 轻量尺寸。需要精确页码的页面可展示总数和总页数；仓库、库存余额等无总数分页页面只展示本页条数、每页条数、上一页、当前页和下一页，不要求后端额外统计总记录数
- 页面跳转保留短时加载遮罩与淡入反馈，侧栏子菜单使用基于内容高度的折叠动画，避免固定最大高度造成布局卡顿
- 所有弹窗受视口最大高度约束，长表单和权限列表必须支持纵向滚动，不能截断底部操作区
- 表格工具栏复用 shadcn `Button` 与 `Tooltip`，可操作按钮悬停时加深背景或边框，禁用批量操作提示需要先选择数据
- 所有 shadcn 文字按钮统一使用 `13px` 常规字重，行内操作、工具栏和弹窗按钮仅通过组件变体与尺寸区分操作层级
- 行内操作颜色按语义统一：详情使用青色强调，编辑使用默认文字色，启用/提交/确认等正向动作使用主色或绿色，停用使用琥珀色，取消/删除使用危险色；采购和仓库模块不得出现整组操作按钮全黑或额外图标混用。
- 新增和编辑表单提交前统一执行 `trim`、必填、长度、格式与数组非空校验；账号、角色编码等唯一字段冲突回填到对应字段，不只显示全局提示
- 查询操作使用短防抖并忽略过期响应，保存、确认、重置密码等异步动作在处理中禁用触发按钮，避免重复请求
- 视觉上避免营销页式大面积装饰，重点突出信息密度、可读性和操作确定性
- 菜单先固定展示，权限以后端接口校验为准，符合 MVP 阶段“页面入口统一展示、进入页面或查询时校验权限码”的数据库设计
- 前端不展示接口文档入口，接口文档只维护在 `docs/api/erp-openapi.yaml`，用于导入 Apifox

### 3.1 业务主数据选项治理标记

当前系统模块的角色、部门、权限码等选项属于小规模配置数据，暂时继续使用 options 接口，不纳入本轮大数据选项改造。产品、客户、供应商、供货关系和业务单据中使用的仓库选择可能随业务增长变大，不能固定取前 100 条作为全量下拉数据。

本轮审计标记如下：

| 状态 | 模块/页面 | 当前实现问题 | 后续处理方法 |
|---|---|---|---|
| 暂不改 | 系统模块 `/system/users`、`/system/roles`、`/system/depts`、`/system/permissions` | 角色、部门、权限码选项数据规模小，当前 options 接口可接受 | 保持现状；未来数据规模增长再改为分页选择 |
| 已改 | 采购供货产品 `/purchase/supplier-products` | 已取消表单全量供应商选项和启用产品前 100 条选项 | 表单供应商、产品均使用动态搜索下拉，按编码或名称请求后端第一页，默认 `pageSize=10`，并缓存已选快照用于回显 |
| 已改 | 采购订单 `/purchase/orders` | 已取消表单全量供应商、启用仓库前 100 条、启用产品前 100 条以及全量启用供货关系预加载 | 供应商、仓库使用动态搜索下拉；采购明细产品按当前供应商分页查询 `supplier_product`，默认 `pageSize=10`，不再预加载全量供货关系 |
| 已改 | 销售订单 `/sales/orders` | 已取消表单全量客户、启用仓库前 100 条、启用产品前 100 条选项 | 客户、仓库、产品均使用动态搜索下拉，按编码或名称请求后端第一页，默认 `pageSize=10`，并缓存订单快照用于编辑回显 |
| 已改 | 库存入库/出库单 `/warehouse/inbound-bills`、`/warehouse/outbound-bills` | 已取消手工补录/调整表单启用产品前 100 条加载，仓库不再只取第一页候选作为固定下拉 | 产品和仓库均使用动态搜索下拉，按编码或名称请求后端第一页，默认 `pageSize=10`，并缓存已选快照用于编辑回显 |
| 已改 | 库存余额 `/warehouse/stocks` | 筛选仓库选项不再取前 100 条或固定第一页后本地过滤 | 仓库筛选使用动态搜索下拉，按仓库编码或名称请求后端第一页，默认 `pageSize=10` |

统一处理方法：继续使用现有普通分页查询模型，不引入 cursor 或无限滚动，也不在下拉中展示 100 条长列表。简单对象选择器做成动态搜索下拉：使用组件库 `Popover + Command/Input + Button` 组合，用户输入编码或名称后短防抖请求第一页，默认 `pageSize=10`，弹层最多展示 10 条；无输入时显示搜索提示，或仅展示后端约定的最近/常用 10 条。复杂对象选择器做成分页选择弹窗：使用组件库 `Dialog`、`DialogScrollArea`、`Input`、`Button`、`Table`、`Pagination`，默认 `pageSize=20`，通过表格分页查看更多；产品、供货关系和订单引用优先使用这种形态。表单编辑已有记录时，已选中的供应商、客户、产品和仓库必须通过单据详情里的快照字段或按 ID 补查独立回显，不能依赖当前选项第一页。

## 4. 权限与登录态设计

系统权限库表设计中，MVP 阶段保留：

```text
sys_dept
sys_user
sys_role
sys_user_role
```

角色权限码直接保存在 `sys_role.permission_codes`，登录后由服务端把用户基础信息、角色编码和权限码放入 Redis session。前端不自行推导权限，只保存服务端返回的当前用户上下文。

请求鉴权采用 Sa-Token 原始 token。登录接口返回 `token` 和 `tokenName`，前端同时持久化两者，并在 Axios 请求拦截器中使用返回的 `tokenName` 作为请求头名称、`token` 作为请求头值；只有兼容旧登录数据时才回退为 `satoken`。应用刷新后通过 `/auth/me` 恢复并校验当前用户，接口返回 `401` 或退出登录时同时清理 token 与 tokenName。

## 5. 基础框架 + 登录模块

首个基础模块包含：

- 登录页 `/login`
- 后台主布局
- 工作台 `/dashboard`
- 路由守卫
- 当前用户状态
- 退出登录
- 404 页面

登录页默认在开发环境填入 `admin / 123456`，方便前端审核。后端接入后，可通过环境变量关闭 mock 登录并切换到真实接口。

后台主布局采用固定左侧菜单。MVP 阶段不设计 `sys_menu` 和动态路由表，权限由后端接口和页面进入后的权限码校验控制。后续如果拆出菜单权限表，可以把当前静态菜单迁移为后端菜单数据。

## 6. 模块生成规则

每生成一个模块，需要同步更新：

```text
docs/frontend-page-design.md
docs/api/erp-openapi.yaml
```

模块页面生成顺序：

```text
1. 基础框架 + 登录模块
2. 系统权限模块
3. 产品模块
4. 仓库库存模块
5. 采购模块
6. 销售模块
7. AI 模块
```

每个模块完成后暂停，由用户审核页面代码和接口文档，再继续下一个模块。

## 7. 系统权限模块：用户管理

### 7.1 页面范围

本次生成系统权限模块中的第一个页面：`/system/users` 用户管理。

页面包含：

- 用户指标概览
- 登录账号、用户姓名、部门、角色、状态独立筛选
- 用户分页表格
- 分页底栏支持选择每页 `10 / 20 / 50` 条，翻页组件相对数据容器居中
- 新增用户
- 编辑用户
- 单独绑定角色
- 批量启用 / 停用账号
- 批量重置密码
- 批量删除用户

对应库表：

```text
sys_user
sys_dept
sys_role
sys_user_role
```

### 7.2 设计考虑

用户管理属于系统基础能力，操作频率不一定最高，但影响权限边界和账号安全，所以页面优先保证清晰、克制、可确认。

页面采用上方指标、筛选区、表格区、弹窗表单的后台标准结构。这样做的好处是：

- 管理员进入页面后能快速知道账号总量、启用数量、超级管理员数量和角色绑定覆盖情况
- 筛选条件和表格保持在同一视线范围内，适合日常查找账号
- 状态筛选值保持接口定义的数字 `0 | 1`，“全部”不发送给接口；部门、角色、状态等多个条件按 AND 组合
- 登录账号和用户姓名分别提交 `username`、`realName`，对应各自数据库字段的包含匹配，不使用跨账号、姓名和部门的关键词混搜
- 新增、编辑和角色绑定使用弹窗，避免频繁跳转
- 行内操作只保留“编辑”和“角色绑定”两个高频动作，减少固定操作列拥挤和悬停裁切风险
- 停用、删除、重置密码放在表格工具栏中作为批量操作，必须先勾选账号再执行，降低误操作风险，也便于后端统一实现批量接口
- 表格字段严格贴合库表和关联关系，后端接入时不需要重新调整页面结构

### 7.3 字段映射

| 页面字段 | 后端来源 |
|---|---|
| 用户ID | `sys_user.id` |
| 登录账号 | `sys_user.username` |
| 用户姓名 | `sys_user.real_name` |
| 所属部门 | `sys_user.dept_id` 关联 `sys_dept.dept_name` |
| 超级管理员 | `sys_user.is_admin` |
| 状态 | `sys_user.status` |
| 最近登录 | `sys_user.last_login_at` |
| 创建时间 | `sys_user.create_time` |
| 更新时间 | `sys_user.update_time` |
| 绑定角色 | `sys_user_role` 关联 `sys_role` |

### 7.4 接口设计考虑

用户管理接口维护在 `docs/api/erp-openapi.yaml` 中，可导入 Apifox。

本页面使用的接口包括：

```text
GET    /system/users
POST   /system/users
GET    /system/users/{userId}
PUT    /system/users/{userId}
PATCH  /system/users/{userId}/status
PATCH  /system/users/{userId}/password
PUT    /system/users/{userId}/roles
DELETE /system/users/{userId}
PATCH  /system/users/batch/status
PATCH  /system/users/batch/password
POST   /system/users/batch/delete
GET    /system/roles/options
GET    /system/depts/options
```

接口字段采用前端友好的 camelCase，后端落库时映射到 snake_case 字段。`bigint` 主键统一用字符串返回，避免前端数字精度丢失。

### 7.5 后端接入方式

当前页面通过模块 API 层的开发环境 mock 数据展示交互效果，页面组件不直接维护临时数据。

后端接口完成后，只需要关闭 `VITE_USE_MOCK_API`，页面继续调用以下 API 函数：

```text
listSystemUsers
createSystemUser
updateSystemUser
updateSystemUserStatus
resetSystemUserPassword
bindSystemUserRoles
deleteSystemUser
batchUpdateSystemUserStatus
batchResetSystemUserPassword
batchDeleteSystemUsers
listRoleOptions
listDeptOptions
```

这些函数已经在 `erp-web/src/modules/system/users/api.ts` 中预留，接口路径与 OpenAPI 文档保持一致。

## 8. 系统权限模块：角色管理

### 8.1 页面范围

本次继续生成系统权限模块中的第二个页面：`/system/roles` 角色管理。

页面包含：

- 角色指标概览
- 角色编码、角色名称、状态筛选
- 角色状态筛选
- 角色分页表格
- 分页底栏支持选择每页 `10 / 20 / 50` 条，翻页组件相对数据容器居中
- 新增角色
- 编辑角色
- 单独配置权限码
- 批量启用 / 停用角色
- 批量删除角色

对应库表：

```text
sys_role
sys_user_role
```

### 8.2 设计考虑

角色管理直接决定用户能访问哪些业务能力，因此页面设计重点是让“角色本身”和“权限码集合”都能被快速看清。

页面沿用用户管理页确认过的后台列表结构：上方指标、筛选区、表格工具栏、数据表格和弹窗表单。这样做的好处是：

- 系统权限模块内的页面交互保持一致，管理员切换用户管理和角色管理时不需要重新学习
- 行内只保留“编辑”和“权限配置”，避免操作列拥挤，也避免悬停浮层被固定列裁切
- 权限码配置单独弹窗处理，符合角色维护的高风险特征，后续接后端时也能独立调用权限更新接口
- 批量启用、批量停用、删除放到表格工具栏，和用户管理页保持一致，便于后端统一实现批量状态和批量删除接口
- 表格中的权限码改为摘要展示，点击“查看明细”打开只读弹窗按权限组展示完整权限码，避免表格被权限码标签撑高

### 8.3 字段映射

| 页面字段 | 后端来源 |
|---|---|
| 角色ID | `sys_role.id` |
| 角色编码 | `sys_role.role_code` |
| 角色名称 | `sys_role.role_name` |
| 权限码 | `sys_role.permission_codes` |
| 状态 | `sys_role.status` |
| 备注 | `sys_role.remark` |
| 创建时间 | `sys_role.create_time` |
| 更新时间 | `sys_role.update_time` |
| 绑定用户数 | `sys_user_role` 按 `role_id` 聚合 |

### 8.4 接口设计考虑

角色管理接口维护在 `docs/api/erp-openapi.yaml` 中，可导入 Apifox。

本页面使用的接口包括：

```text
GET    /system/roles
POST   /system/roles
GET    /system/roles/{roleId}
PUT    /system/roles/{roleId}
PATCH  /system/roles/{roleId}/status
PATCH  /system/roles/{roleId}/permissions
DELETE /system/roles/{roleId}
PATCH  /system/roles/batch/status
POST   /system/roles/batch/delete
```

接口字段继续采用 camelCase，后端落库时映射到 snake_case 字段。角色查询条件按库表字段拆为 `roleCode`、`roleName`、`status`，不使用跨字段关键词混搜，避免后端在 `role_code`、`role_name`、`remark` 上写过宽的查询逻辑。`permissionCodes` 对应数据库 JSON 字段，前端以字符串数组维护；`userCount` 不落库，由后端根据 `sys_user_role` 聚合返回。

### 8.5 后端接入方式

当前页面通过模块 API 层的开发环境 mock 数据展示交互效果，页面组件不直接维护临时数据。

后端接口完成后，只需要关闭 `VITE_USE_MOCK_API`，页面继续调用以下 API 函数：

```text
listSystemRoles
getSystemRole
createSystemRole
updateSystemRole
updateSystemRoleStatus
updateSystemRolePermissions
deleteSystemRole
batchUpdateSystemRoleStatus
batchDeleteSystemRoles
```

这些函数已经在 `erp-web/src/modules/system/roles/api.ts` 中预留，接口路径与 OpenAPI 文档保持一致。

## 9. 系统权限模块：部门管理

### 9.1 页面范围

本次继续生成系统权限模块中的第三个页面：`/system/depts` 部门管理。

页面包含：

- 部门指标概览
- 部门名称、状态筛选
- 部门树形表格
- 新增部门
- 新增下级部门
- 编辑部门
- 批量启用 / 停用部门
- 删除部门

对应库表：

```text
sys_dept
sys_user
```

### 9.2 设计考虑

部门管理的核心不是分页列表，而是维护组织层级。后端只需要返回 `sys_dept` 扁平列表，前端根据 `parent_id` 构建树形结构，并在页面侧计算层级路径。

这样设计的好处是：

- 管理员能直接看到部门和小组之间的父子关系，不需要在分页列表里反复搜索上级部门
- 新增下级部门放在行内，是部门管理的高频动作；编辑和删除也保持在行内，操作路径短
- 删除前检查是否存在下级部门和员工归属，符合后端实际约束，避免误删组织节点
- 顶部指标展示部门总数、启用部门、下级部门和员工数量，让管理员快速判断组织数据是否完整
- 筛选条件按 `sys_dept` 字段拆为 `deptName` 和 `status`，前端查询时必须把有效条件传给 `GET /system/depts`，避免后端写过宽的关键词查询，也避免全量拉取后本地筛选
- 表格部门列只展示部门名称和层级展开控件，避免把后端长 ID 堆在名称下面
- 现有 `sys_dept` 表没有部门编码字段，所以本阶段不展示、不编辑、不查询部门编码；后续如果库表新增 `dept_code`，再把它补入筛选区和编辑弹窗
- 树形层级由前端固定展开按钮和占位控制，确保同一级部门在收起、展开后左右间隔一致
- 勾选父级部门时自动勾选其下级部门，避免批量启停时漏掉组织分支内的子节点
- 停用父级部门时必须级联停用全部下级；上级仍停用时，不允许单独启用下级。前端负责交互预校验，后端接口负责最终约束
- 批量停用或在编辑弹窗中把部门改为停用时，必须先弹出简洁的风险确认，说明下级部门会同步停用、员工账号不会自动停用；精确影响范围和最终操作权限由后端基于最新数据判断
- 无父节点统一显示为“无上级部门”，不使用“顶级部门”等口语化命名

### 9.3 字段映射

| 页面字段 | 后端来源 |
|---|---|
| 部门ID | `sys_dept.id`，仅用于接口主键和前端行标识，不直接展示为业务编码 |
| 上级部门 | `sys_dept.parent_id` 关联 `sys_dept.id` |
| 祖级路径 | `sys_dept.ancestors`，接口可原样返回；页面展示路径由前端按 `parent_id` 递归计算 |
| 部门名称 | `sys_dept.dept_name` |
| 状态 | `sys_dept.status` |
| 创建时间 | `sys_dept.create_time` |
| 更新时间 | `sys_dept.update_time` |
| 员工数量 | `sys_user` 按 `dept_id` 聚合，接口字段暂沿用 `userCount` |

### 9.4 接口设计考虑

部门管理接口维护在 `docs/api/erp-openapi.yaml` 中，可导入 Apifox。

本页面使用的接口包括：

```text
GET    /system/depts?deptName={deptName}&status={status}
POST   /system/depts
GET    /system/depts/{deptId}
PUT    /system/depts/{deptId}
PATCH  /system/depts/{deptId}/status
DELETE /system/depts/{deptId}
PATCH  /system/depts/batch/status
POST   /system/depts/batch/delete
GET    /system/depts/options
```

接口字段继续采用 camelCase，后端落库时映射到 snake_case 字段。`GET /system/depts` 返回扁平数组，不能返回预组装的 `children`；前端按 `parentId` 构建树表和层级路径。部门名称和状态筛选由后端执行，前端只发送有效查询参数：`deptName` 提交前 `trim`，空字符串不发送；状态为“全部”时不发送 `status`，不得发送 `all`。查询、重置、刷新以及新增/编辑/启停/删除后的回查都复用当前查询条件。`userCount` 不落库，由后端根据 `sys_user.dept_id` 聚合返回。部门状态接口必须执行父子级联约束。

### 9.5 后端接入方式

当前页面通过模块 API 层的开发环境 mock 数据展示交互效果。mock 接口同样返回扁平部门列表，用于验证真实后端接入边界。

后端接口完成后，只需要关闭 `VITE_USE_MOCK_API`，页面继续调用以下 API 函数：

```text
listSystemDepts
getSystemDept
createSystemDept
updateSystemDept
updateSystemDeptStatus
deleteSystemDept
batchUpdateSystemDeptStatus
batchDeleteSystemDepts
```

这些函数已经在 `erp-web/src/modules/system/depts/api.ts` 中预留，接口路径与 OpenAPI 文档保持一致。

## 10. 系统权限模块：权限码配置

### 10.1 页面范围

本次生成系统权限模块第四个页面：`/system/permissions` 权限码配置。

页面包含权限指标概览、权限码/权限名称/模块/操作类型/状态独立筛选、分页列表、新增与编辑、单条启停、批量启停、批量删除和角色引用保护。筛选查询使用 250ms 防抖，所有写操作均设置提交锁，防止重复请求。

### 10.2 字段与校验

| 页面字段 | 后端来源 | 校验规则 |
|---|---|---|
| 权限码 | `sys_permission.permission_code` | 必填、唯一、最长 100；小写字母或数字按冒号分段；创建后不可修改 |
| 权限名称 | `sys_permission.permission_name` | 必填、最长 100 |
| 所属模块 | `sys_permission.module_code` | 必填、最长 64 |
| 操作类型 | `sys_permission.action_type` | 必填，限定 query/create/update/delete/manage/execute |
| 状态 | `sys_permission.status` | 必填，1 启用、0 停用 |
| 排序值 | `sys_permission.sort_order` | 0-9999 整数 |
| 权限说明 | `sys_permission.description` | 选填、最长 500 |
| 模块名称 | 前端根据 `sys_permission.module_code` 和固定模块字典映射 | 不要求后端返回 `moduleName` |
| 角色引用数 | 后端根据 `sys_role.permission_codes` JSON 聚合 | 只读，不落库；通配符 `*` 视为引用全部权限码 |

权限码由后端鉴权逻辑和角色授权共同引用，创建后不可修改，更新接口也不接收 `permissionCode`；删除前必须确认没有角色引用。保留权限 `*` 仅供超级管理员使用，不在普通权限目录中维护。

停用权限码时必须说明授权链路影响：相关角色将不再授予该权限，绑定这些角色的用户也将无法执行对应操作，后端应清理受影响用户的权限 Session。编辑弹窗内触发停用确认时，底层表单必须冻结，用户只能处理当前确认弹窗。

### 10.3 接口

```text
GET    /system/permissions
POST   /system/permissions
GET    /system/permissions/{permissionId}
PUT    /system/permissions/{permissionId}
PATCH  /system/permissions/{permissionId}/status
DELETE /system/permissions/{permissionId}
PATCH  /system/permissions/batch/status
POST   /system/permissions/batch/delete
GET    /system/permissions/options
```

`GET /system/permissions/options` 只返回启用权限，并按模块分组供角色授权弹窗使用。权限码唯一性冲突返回 `409 Conflict`；删除仍被角色引用的权限码也返回 `409 Conflict`。

权限列表分别使用 `permissionCode`、`permissionName` 查询权限码和权限名称，模块、操作类型、状态使用精确筛选；所有有效条件按 AND 组合，不提供跨字段 `keyword`。

## 11. 前端风格规范

后续页面的视觉和交互细节统一参考 `docs/frontend-style-guide.md`。

当前确认的风格方向是：深色应用壳与浅色内容区形成稳定层级；页面使用单条摘要栏、紧凑筛选区和一个数据容器；主操作使用品牌蓝，普通行内操作使用 `ghost`，危险操作仅保留红色文字；状态标签使用低饱和浅色语义；表格行内只保留少量高频操作；批量操作放在表格工具栏；树形数据优先使用树表，不强行分页。

## 12. 产品模块：产品分类

### 12.1 页面范围

产品分类页面路径为 `/product/categories`，包含分类指标概览、分类名称和状态筛选、分类树表、新增分类、新增下级、编辑、批量启停、批量删除和刷新。

对应库表：

```text
product_category
product
```

### 12.2 页面与数据边界

- `GET /product/categories` 接收 `categoryName`（包含匹配）、`status`（精确匹配）两个独立查询参数；前端提交前先 `trim` 分类名称，空字符串、`all` 和未选择状态不进入请求；后端按有效条件过滤并返回命中分类及其全部祖先组成的扁平数组，字段只包含分类表字段、时间字段以及可通过 `product.category_id` 聚合得到的 `productCount`
- 后端不返回 `children`、`parentName` 或 `categoryPath`，前端按 `parentId` 构建分类树、上级名称和层级路径
- 查询按钮使用 250ms 防抖；点击后立即显示按钮转圈和数据区加载层，防抖等待期也必须有反馈；列表请求使用序号忽略过期响应，刷新、新增、编辑、删除和批量状态变更后的回查都复用当前已应用筛选条件，不无条件拉取全量分类；保存和批量操作使用提交锁
- 当前列表、分类总数、启用分类、下级分类、关联产品指标均基于本次接口返回的扁平数组计算；后端返回筛选结果时，前端不再二次按名称或状态过滤，只按 `parentId` 组树展示；表格底部显示“命中 X 条”
- 分类名称提交前执行 `trim`、非空和 100 字符长度校验；同一上级下重名时回填分类名称字段错误
- 勾选父级时同步选中全部下级；停用父级时级联停用全部下级和这些分类下的关联产品；上级停用时不能单独启用下级
- 批量停用或编辑停用前必须显示简洁的风险确认，说明下级分类和关联产品会同步停用；前端不计算精确影响数量，确认后由后端基于最新数据在同一事务中完成校验和级联
- 编辑分类时，上级选项排除当前分类及全部下级，前后端都要防止形成循环层级
- 存在下级分类或关联产品时禁止删除，前端预检查，后端最终校验并返回 `409 Conflict`

### 12.3 字段映射

| 页面字段 | 后端来源 |
|---|---|
| 分类ID | `product_category.id` |
| 上级分类ID | `product_category.parent_id` |
| 分类名称 | `product_category.category_name` |
| 状态 | `product_category.status` |
| 创建时间 | `product_category.create_time` |
| 更新时间 | `product_category.update_time` |
| 产品数量 | `product` 按 `category_id` 聚合未删除产品数量，只统计直接关联产品 |
| 上级分类名称 | 前端根据扁平分类数组映射 |
| 层级路径 | 前端根据 `parentId` 递归计算 |

### 12.4 接口

```text
GET    /product/categories?categoryName={categoryName}&status={status}
POST   /product/categories
GET    /product/categories/{categoryId}
PUT    /product/categories/{categoryId}
PATCH  /product/categories/{categoryId}/status
DELETE /product/categories/{categoryId}
PATCH  /product/categories/batch/status
POST   /product/categories/batch/delete
```

查询接口需要 `product:query`，写接口需要 `product:manage`。当前开发环境通过模块 API 层提供扁平 mock 数据；关闭 `VITE_USE_MOCK_API` 后，页面继续使用相同接口契约。

## 13. 产品模块：产品档案

### 13.1 页面范围

产品档案页面路径为 `/product/products`，包含产品指标概览、产品编码/产品名称/品牌/条码/分类/状态独立筛选、分页列表、新增与编辑、单条启停和删除、批量启停和批量删除。

### 13.2 页面与数据边界

- 产品列表来源于 `product`，分类展示名称由后端按 `product.category_id` 关联 `product_category.category_name` 返回；后端不拼接分类层级路径
- 产品分类选项继续使用 `/product/categories` 的扁平数据，前端按 `parentId` 生成表单中的分类路径
- 分类下拉显示的 `办公用品 / 办公纸品` 路径由前端生成；请求只发送 `categoryId`，产品接口不返回路径字符串
- 查询父分类时必须包含该分类及全部后代分类下的产品，后端按最新 `product_category.parent_id` 关系解析查询范围
- 产品编码、产品名称、品牌名称和条码使用独立参数；编码、名称、品牌为包含匹配，条码为精确匹配，多个条件按 AND 组合
- 查询使用 250ms 防抖；分页切换使用 180ms 防抖，点击查询、上一页、下一页、页码或每页条数后立即显示数据区加载层并锁定分页控件
- 产品编码由后端在创建时生成，新增弹窗仅提示“保存后由系统生成”，编辑弹窗只读展示且创建、编辑请求都不提交编码；产品名称和单位名称必填，所有文本提交前 `trim`，价格和安全库存不得小于 0
- 启用产品不能归属停用分类，前端做快速校验，后端在新增、编辑、单条启用和批量启用时基于最新分类状态再次校验
- 产品允许暂不分类；接口在 `categoryId` 为空时同步返回 `categoryName: null`，前端统一展示为“未分类”，后端无需拼装空字符串或分类层级路径
- 停用产品前提示该产品不能继续用于新建采购单或销售单，历史业务数据不受影响；编辑弹窗内停用时底层表单进入 `inert` 状态
- 删除仅允许未被库存、采购、销售等业务数据引用的产品；前端只显示通用风险说明，后端执行最终关联校验，存在引用时返回 `409 Conflict`

### 13.3 字段映射

| 页面字段 | 后端来源 |
|---|---|
| 产品ID | `product.id` |
| 产品编码 | `product.product_code` |
| 产品名称 | `product.product_name` |
| 分类ID | `product.category_id` |
| 分类名称 | 按 `category_id` 关联 `product_category.category_name` |
| 品牌、单位、规格、条码 | `product.brand_name`、`unit_name`、`specification`、`barcode` |
| 参考采购价、参考销售价 | `product.reference_purchase_price`、`reference_sale_price` |
| 安全库存 | `product.safety_stock_qty` |
| 状态、备注、时间 | `product.status`、`remark`、`create_time`、`update_time` |
| 分类层级路径 | 前端根据分类扁平数组计算，不要求产品接口返回 |

### 13.4 接口

```text
GET    /products
POST   /products
GET    /products/{productId}
PUT    /products/{productId}
PATCH  /products/{productId}/status
DELETE /products/{productId}
PATCH  /products/batch/status
POST   /products/batch/delete
```

查询接口需要 `product:query`，写接口需要 `product:manage`，认证请求头名称使用登录响应返回的 Sa-Token `tokenName`。

## 14. 仓库库存模块：仓库管理

### 14.1 页面范围

仓库管理页面路径为 `/warehouse/warehouses`，包含仓库指标概览、仓库编码/仓库名称/联系人/联系电话/状态独立筛选、分页列表、新增与编辑、单条启停和删除、批量启停和批量删除。

### 14.2 页面与数据边界

- 页面字段全部来源于 `warehouse` 表，不要求后端返回前端可计算的展示字段。
- 仓库编码、名称、联系人和联系电话分别使用包含匹配，状态使用精确匹配，多个有效条件按 AND 组合，不提供跨字段 `keyword`。
- 仓库编码由后端创建时统一生成并由唯一索引兜底；新增弹窗只显示“保存后由系统生成”，创建和编辑请求都不提交 `warehouseCode`，编辑时仅只读展示已有编码。
- 仓库名称修改时，后端在同一事务内同步 `warehouse_stock.warehouse_name`；历史 `stock_bill.warehouse_name` 继续作为业务发生时的快照保留。
- 停用仓库前提示该仓库不能继续用于新建采购、销售、退货和库存调整业务，历史单据与现有库存不受影响；编辑弹窗内停用时底层表单进入 `inert` 状态。
- 删除只允许无库存余额且无入库单、出库单和库存流水记录的仓库；前端显示通用风险说明，后端执行最终关联校验并在存在引用时返回 `409 Conflict`。
- 查询使用 250ms 防抖，分页使用 180ms 防抖；点击后立即显示数据区加载层并锁定重复操作，写操作统一使用提交锁。

### 14.3 字段映射

| 页面字段 | 后端来源 |
|---|---|
| 仓库ID | `warehouse.id`，BIGINT 按字符串传输 |
| 仓库编码 | `warehouse.warehouse_code`，后端创建时生成，创建后只读 |
| 仓库名称 | `warehouse.warehouse_name` |
| 联系人、联系电话 | `warehouse.contact_name`、`warehouse.contact_phone` |
| 仓库地址 | `warehouse.address` |
| 状态、备注 | `warehouse.status`、`warehouse.remark` |
| 创建、更新时间 | `warehouse.create_time`、`warehouse.update_time` |

### 14.4 接口

```text
GET    /warehouse/warehouses
POST   /warehouse/warehouses
GET    /warehouse/warehouses/{warehouseId}
PUT    /warehouse/warehouses/{warehouseId}
PATCH  /warehouse/warehouses/{warehouseId}/status
DELETE /warehouse/warehouses/{warehouseId}
PATCH  /warehouse/warehouses/batch/status
POST   /warehouse/warehouses/batch/delete
```

查询接口需要 `warehouse:query`，写接口需要 `warehouse:manage`。仓库编码由后端生成并由唯一索引兜底，删除引用保护返回 `409 Conflict`。仓库管理列表使用无总数分页，后端不需要为了底部分页额外查询总记录数；顶部摘要只展示本页启用、停用、联系方式完整和联系方式待补等业务指标。

## 15. 仓库库存模块：库存管理

### 15.1 页面范围

库存管理页面路径为 `/warehouse/stocks`，用于只读查询仓库与产品维度的当前库存余额，包含库存记录摘要、仓库/产品编码/产品名称/库存健康/占用情况独立筛选、分页列表和刷新操作。库存变化必须由采购入库、销售出库、退货或库存调整形成，当前页不提供直接修改库存入口。

### 15.2 页面与数据边界

- 当前库存和锁定库存来源于 `warehouse_stock.stock_qty`、`warehouse_stock.locked_qty`，可用库存由服务层计算 `stock_qty - locked_qty`。
- 安全库存来源于关联的 `product.safety_stock_qty`；低库存按 `0 < available_qty <= safety_stock_qty` 判断，不新增库存预警表。
- 库存健康和占用情况是两个独立派生维度，不在 `warehouse_stock` 增加单一状态字段。同一条库存可同时显示“低库存 + 部分锁定”或“无可用库存 + 全部锁定”。
- 风险行使用低饱和背景辅助识别：低库存和无可用库存使用浅黄色，零库存使用浅红色；状态标签仍保留对应文字，不能只依赖颜色表达。
- 仓库使用 `warehouseId` 精确筛选，产品编码和产品名称分别使用包含匹配，库存健康和占用情况分别使用明确派生条件筛选，多个条件按 AND 组合，不提供跨字段 `keyword`。
- 不同产品的单位可能不同，摘要区只展示涉及仓库数、涉及产品数、低库存记录数、锁定记录数等当前页业务信号，不展示当前页库存记录数，也不跨产品汇总库存数量。
- 页面展示 `warehouse_stock` 中的仓库、产品和单位快照；产品或仓库名称变更时由后端按数据库规则维护当前余额快照，历史出入库凭证仍保留业务发生时快照。
- 查询、重置、刷新和分页均使用短防抖，点击后立即显示数据区加载层并锁定重复操作。

### 15.3 字段映射

| 页面字段 | 后端来源 |
|---|---|
| 库存ID | `warehouse_stock.id`，BIGINT 按字符串传输 |
| 仓库ID、编码、名称 | `warehouse_stock.warehouse_id`、`warehouse_code`、`warehouse_name` |
| 产品ID、编码、名称 | `warehouse_stock.product_id`、`product_code`、`product_name` |
| 单位 | `warehouse_stock.unit_name` |
| 当前库存 | `warehouse_stock.stock_qty` |
| 锁定库存 | `warehouse_stock.locked_qty` |
| 可用库存 | 服务层计算 `stock_qty - locked_qty` |
| 安全库存 | 关联 `product.safety_stock_qty` |
| 库存健康 | 根据当前库存、可用库存和安全库存派生：正常、低库存、无可用库存、零库存 |
| 占用情况 | 根据当前库存和锁定库存派生：未锁定、部分锁定、全部锁定 |
| 更新时间 | `warehouse_stock.update_time` |

### 15.4 接口

```text
GET /warehouse/stocks
```

接口需要 `warehouse:query` 权限。库存余额采用无总数分页，后端不需要返回精确 `total`，也不需要为了列表执行额外 `COUNT(*)`；顶部响应摘要只基于当前页 `records` 派生业务指标，不展示当前页记录数。

## 16. 仓库库存模块：入库单与出库单

### 16.1 页面范围

入库单页面路径为 `/warehouse/inbound-bills`，出库单页面路径为 `/warehouse/outbound-bills`。两个页面复用同一套页面组件，但在导航、标题、筛选类型、确认按钮和数量文案上按方向区分。

入库单承接采购入库、销售退货入库和调整入库；出库单承接销售出库、采购退货出库和调整出库。采购订单或销售订单审核后只生成 `PENDING_CONFIRM` 的入库单/出库单，不直接改变库存；仓库人员确认本次数量后才生成库存流水并更新 `warehouse_stock`。

### 16.2 页面与数据边界

- 入库单主列表来源于 `inbound_bill`，明细来源于 `inbound_bill_item`；出库单主列表来源于 `outbound_bill`，明细来源于 `outbound_bill_item`。
- 入库单和出库单都必须展示来源对象，但页面文案不能使用泛化“往来方”：入库单主列表来源对象列固定命名为“供应商”，出库单主列表来源对象列固定命名为“客户”；库存调整单的来源对象显示为“调整仓库”，取受影响仓库名称快照。
- 列表采用“单据父行 + 商品明细区”的主从表格：父行只按入库单/出库单主表字段拆成独立列，展示单号、类型、录入方式、来源类型、来源单号、供应商或客户、仓库、状态、负责人、创建时间和操作；不得把“类型/来源”“供应商或客户/仓库”“状态/操作”等不同字段混在同一列。字段较多时通过横向滚动承载，不压缩为组合文案。
- 商品明细区默认全部收起，父行提供展开/收起明细控制；展开后使用紧凑明细表展示产品编码、产品名称、单位、本次入库量或出库量、合格数量、不合格数量、确认本单后的剩余未入库/未出库和明细备注。来源生成的待确认入库单/出库单初始本次数量为 0 或空业务值，由仓库人员在确认前按实物到货或可出库数量填写；合格数量和不合格数量必须按 `qualified_qty`、`defective_qty` 拆成独立列，不得拼接成“质检”汇总字段；非采购入库、非销售退货入库的单据类型在质检数量列显示 `-`。
- 入库单/出库单属于宽表时，表格区域自身必须有可滚动视口，表头固定在当前表格顶部且背景必须为不透明实底色，只滚动数据行；每页 10 条主单在默认明细收起时应尽量完整展示，超过一页高度后才使用表格内纵向滚动。
- 父行的入库量/出库量只展示同单位总量或“若干条商品”，不得把产品编码、产品名称和多个明细数量拼进父行数量列；商品明细由前端在当前页按需调用详情接口补齐，不要求列表接口额外返回完整明细。
- 详情必须展示本单对来源订单明细的影响：计划数量、生成本单前累计已处理、本次数量和确认本单后剩余未处理。入库页面文案为采购数量、累计已入库、本次入库数量、确认后剩余未入库；出库页面文案为销售数量、累计已出库、本次出库数量、确认后剩余未出库。
- 单据号和来源单号分别使用包含匹配，仓库、类型、录入方式和状态使用精确匹配；录入方式提供来源生成、人工补录、人工调整三项，多个有效条件按 AND 组合，不提供跨字段 `keyword`。
- 入库类型为 `PURCHASE_IN`、`SALES_RETURN`、`ADJUST_IN`，出库类型为 `SALES_OUT`、`PURCHASE_RETURN`、`ADJUST_OUT`；状态严格使用数据库值 `DRAFT`、`PENDING_CONFIRM`、`CONFIRMED`、`CANCELLED`。
- 顶部摘要复用全站列表页的单条摘要栏样式，只统计当前页单据数、待确认数、已确认数和已取消数。不同产品可能使用不同单位，父行入库量/出库量只可展示单一单位合计或商品条数，不得跨单位形成虚假的总数。
- 详情展示 `plan_qty`、`processed_qty`、`current_qty`、`pending_qty`、`qualified_qty`、`defective_qty`；其中 `pending_qty` 表示后端按 `plan_qty - processed_qty - current_qty` 计算出的确认本单后来源订单明细预计剩余未入库/未出库数量，只读展示，不得由用户手动填写。确认后可展示关联库存流水的 `before_qty`、`change_qty` 和 `after_qty`。
- 仓库名称、产品编码、产品名称和单位使用业务发生时保存的快照字段，后续主数据改名不回写历史凭证。
- 正常采购、销售和退货单由来源单据审核后生成 `PENDING_CONFIRM` 入库单或出库单，不生成草稿；原业务遗漏登记时，新增按钮允许按方向补录对应入库单或出库单草稿。补录必须填写原业务单号和补录原因，列表通过“录入方式”列展示来源生成、人工补录或人工调整，详情展示具体录入方式，`source_id` 为空，来源类型由单据类型推导。
- 库存调整按方向进入入库单或出库单页面：调整入库使用 `ADJUST_IN`，调整出库使用 `ADJUST_OUT`，来源类型固定为 `STOCK_ADJUST`，调整单号由后端生成，调整原因必填，来源对象快照保存受影响仓库名称。
- 手工补录和库存调整的负责人由后端按当前登录用户写入，页面只读展示，不允许人工代填；列表中的创建信息排列在确认信息前。
- 数量输入按产品 `quantity_precision` 控制步长和校验。箱、瓶等精度为 0 的产品按 1 增减且拒绝小数；kg 等精度为 2 的产品按 0.01 增减。接口传业务真实值，后端按 100 倍整数写入数据库。
- 编辑弹窗必须按状态控制字段：`DRAFT` 可修改仓库；手工补录和库存调整草稿可维护产品明细、来源/调整原因、数量和备注；来源生成单据不能增删或更换产品。进入 `PENDING_CONFIRM` 后仓库、来源信息和产品结构锁定，只允许有权限人员调整本次数量、合格数量、不合格数量和备注。`CONFIRMED` 后不允许任何修改。
- 点击提交确认、确认入库或确认出库时，列表按钮只能打开详情弹窗；详情必须展示完整单头和全部明细，并在详情底部提供对应动作按钮，再弹出二次确认。不得在列表上直接执行提交或确认。
- 明细备注过长时列表和详情只显示摘要，通过组件库 Tooltip 或等价悬浮层展示完整内容。
- `DRAFT` 行显示编辑、提交确认和取消；`PENDING_CONFIRM` 行显示编辑、确认入库/出库和取消。来源业务单据生成的待确认单只能编辑本次数量、质量数量和备注；手工补录和库存调整草稿允许增删产品明细。
- 状态不使用普通下拉任意修改，只允许 `DRAFT -> PENDING_CONFIRM`、`PENDING_CONFIRM -> CONFIRMED` 或 `DRAFT/PENDING_CONFIRM -> CANCELLED`。提交确认前必须校验仓库、来源/原因、产品明细、本次数量和质检数量，不改变库存；确认接口在事务中更新库存、生成库存流水并回写来源单据；已确认单据出现错误时新增反向调整，不直接修改历史状态。
- 查询、重置、刷新和分页均使用短防抖，点击后立即显示数据区加载层并锁定重复操作；详情加载使用独立状态。

### 16.3 字段映射

| 页面字段 | 后端来源 |
|---|---|
| 入库单ID、单号 | `inbound_bill.id`、`inbound_bill.inbound_no`，BIGINT ID 按字符串传输 |
| 出库单ID、单号 | `outbound_bill.id`、`outbound_bill.outbound_no`，BIGINT ID 按字符串传输 |
| 入库/出库类型 | `inbound_bill.inbound_type`、`outbound_bill.outbound_type` |
| 来源类型、ID、单号 | `source_type`、`source_id`、`source_no` |
| 供应商/客户/调整仓库 | `source_party_id`、`source_party_name`；库存调整时保存受影响仓库 ID 和名称快照 |
| 录入方式 | `entry_mode`：来源生成、人工补录、人工调整 |
| 仓库ID、名称 | `warehouse_id`、`warehouse_name` |
| 状态 | `status` |
| 明细数 | 按入库单或出库单明细聚合 |
| 确认人、确认时间 | `confirmed_by_id`、`confirmed_by_name`、`confirmed_at` |
| 创建人、创建时间 | `created_by_id`、`created_by_name`、`create_time` |
| 负责人、补录/调整原因 | `responsible_by_id`、`responsible_by_name`、`manual_reason` |
| 产品及单位精度快照 | 明细表 `product_id`、`product_code`、`product_name`、`unit_name`、`quantity_precision` |
| 计划、累计、本次、剩余数量 | `plan_qty`、`processed_qty`、`current_qty`、`pending_qty` |
| 合格、不合格数量 | `qualified_qty`、`defective_qty` |

### 16.4 接口

```text
GET /warehouse/inbound-bills
POST /warehouse/inbound-bills
GET /warehouse/outbound-bills
POST /warehouse/outbound-bills
GET /warehouse/stock-bills/{stockBillId}
PUT /warehouse/stock-bills/{stockBillId}
POST /warehouse/stock-bills/{stockBillId}/submit
POST /warehouse/stock-bills/{stockBillId}/confirm
POST /warehouse/stock-bills/{stockBillId}/cancel
```

## 17. 仓库库存模块：库存调整（合并至入库单/出库单）

库存调整不新增独立页面。调整入库在入库单页面（`/warehouse/inbound-bills`）中创建 `ADJUST_IN`，调整出库在出库单页面（`/warehouse/outbound-bills`）中创建 `ADJUST_OUT`，并通过 `entryMode=MANUAL_ADJUSTMENT` 筛选调整单据。

### 17.1 数据与接口边界

- 不新增库存调整专表，复用 `inbound_bill` / `outbound_bill` 与对应明细；确认后生成 `stock_bill` / `stock_bill_item`。库存调整是单仓库库存增减，不自动生成反向入库单或出库单；如果业务需要 A 仓到 B 仓移动，应后续新增“库存调拨单”来关联调出和调入两边。
- 调整类型只允许 `ADJUST_IN` 和 `ADJUST_OUT`，来源类型固定为 `STOCK_ADJUST`；调整流水号和调整单号由后端生成；`source_party_id/name` 记录受影响仓库 ID 和名称快照，前端显示为“调整仓库”。
- 入库单/出库单页面同时展示来源生成单据、人工补录单据和库存调整单据，通过录入方式筛选区分。
- 摘要基于当前页 `records` 统计待确认、已确认、已取消和来源生成等业务指标，不展示当前页单据数，不跨不同产品单位汇总数量；分页器是否展示总数由对应列表契约决定。

### 17.2 页面操作

- 新增入库单时可选调整入库，新增出库单时可选调整出库，负责人由后端按当前登录用户写入，前端只读展示；调整原因和至少一条产品明细必填。
- 数量输入继续按产品 `quantity_precision` 控制，接口传业务真实值，后端按 100 倍整数持久化。
- 草稿允许编辑仓库、产品、数量、明细备注、调整原因和凭证备注；调整方向、流水号和调整单号创建后不可修改。
- 草稿提交确认后进入待确认状态但不改变库存；确认使用独立动作和二次确认，后端在事务内校验库存、更新余额并生成库存流水；取消只允许草稿或待确认状态执行且不改变库存。
- 已确认调整不能直接取消或改回草稿，发现错误时创建相反方向的库存调整保留完整追溯链路；该反向调整由用户显式创建，不由系统在确认原调整时自动生成。
- 长表单和详情使用共享 `DialogScrollArea`，查询、刷新、重置和分页使用统一防抖加载层。

### 17.3 接口

库存调整复用入库单/出库单接口，不新增重复端点：

```text
GET /warehouse/inbound-bills?entryMode=MANUAL_ADJUSTMENT
GET /warehouse/outbound-bills?entryMode=MANUAL_ADJUSTMENT
POST /warehouse/stock-bills
GET /warehouse/stock-bills/{stockBillId}
PUT /warehouse/stock-bills/{stockBillId}
POST /warehouse/stock-bills/{stockBillId}/submit
POST /warehouse/stock-bills/{stockBillId}/confirm
POST /warehouse/stock-bills/{stockBillId}/cancel
```

## 18. 采购业务模块：供应商、供货产品与采购订单

### 18.1 页面范围

采购业务模块包含 `/purchase/suppliers` 供应商管理、`/purchase/supplier-products` 供货产品和 `/purchase/orders` 采购订单三个页面。三个页面都必须复用全站列表页结构：单条摘要栏、紧凑筛选区、固定表头表格、弹窗表单、详情弹窗和语义化行内操作。

### 18.2 页面与数据边界

- 供应商管理主表字段来自 `supplier`；供货产品主表字段来自 `supplier_product`，同时展示供应商和产品快照；采购订单主表字段来自 `purchase_order`，采购明细来自 `purchase_order_item`。
- 查询区使用全站 `filter-grid` 紧凑布局，采购模块统一使用与产品、系统权限模块接近的筛选控件宽度；供应商编码、供应商名称、产品名称、采购单号等独立参数按接口字段提交，不提供跨字段 `keyword`。
- 详情弹窗优先使用 shadcn-vue 的 `Dialog`、`DialogScrollArea`、`Badge`、`Table` 和全局详情字段样式，不手写临时大块布局。字段按数据库表拆分显示，不把多个业务字段拼成一个混合列。
- 供应商详情和供货产品详情要用分组字段块展示基础信息、联系信息、评分、创建/更新时间和备注；长备注只在字段块内自动换行或摘要展示，避免撑破弹窗。
- 采购订单列表列宽按数据含义分配：采购单号、供应商、入库仓库、状态、订单金额、预计到货、创建人、更新时间和操作必须分列；字段较多时允许横向滚动，但不能把供应商、仓库、状态或操作混在同一列。
- 采购订单新增/编辑弹窗只在单头维护 `expectedArrivalDate`，标签显示为“预计到货”，下方展示日期示例；采购明细不再出现预计到货字段。
- 采购订单单头供应商和明细产品必须按启用的 `supplier_product` 双向过滤：先选供应商时，明细产品下拉只展示该供应商维护的启用供货产品；先选产品时，供应商下拉只展示能供应当前全部明细产品的供应商；切换供应商时要同步更新或清空不匹配明细，避免生成虚假的供应商-产品组合。
- 采购订单明细产品必须按已选供应商、产品编码或产品名称请求 `supplier_product` 分页候选，默认 `pageSize=10`；不得预加载启用供货关系全集后在前端本地过滤，也不得只拿第一页结果当作全量关系。
- 采购明细表格复用组件库表格，产品、数量、采购价、推荐分、小计、备注等列必须和数据居中或按语义对齐；数值列居中或右对齐，不能出现表头和数据错位。
- `DRAFT` 采购单允许采购创建权限编辑；`SUBMITTED` 采购单仍可由具备审核/审批权限的人编辑供应商、入库仓库、预计到货、备注和明细；`APPROVED`、`PARTIAL_INBOUND`、`INBOUND_DONE`、`CANCELLED` 不允许直接编辑。
- 提交和审核不能在列表上直接执行。点击“提交”或“审核”时必须先打开采购单详情弹窗，展示完整单头和全部采购明细；详情底部提供对应动作按钮，再弹出二次确认。预计到货为空时，详情底部显示明确提示并禁用提交/审核按钮。
- 采购订单提交时前端先校验单头预计到货、供应商、入库仓库和明细；后端仍必须在提交和审核接口中重复校验。审核通过只生成仓库模块待确认入库单，不直接改变库存。
- 采购订单、供应商和供货产品都是分页列表，顶部摘要只展示当前页业务指标，不展示当前页记录数，也不得把“全筛选范围统计”放进摘要栏；分页器总数仅用于页码导航，不参与摘要。

### 18.3 接口

```text
GET /purchase/suppliers
POST /purchase/suppliers
GET /purchase/suppliers/{supplierId}
PUT /purchase/suppliers/{supplierId}
PATCH /purchase/suppliers/{supplierId}/status
GET /purchase/supplier-products
POST /purchase/supplier-products
GET /purchase/supplier-products/{supplierProductId}
PUT /purchase/supplier-products/{supplierProductId}
PATCH /purchase/supplier-products/{supplierProductId}/status
GET /purchase/orders
POST /purchase/orders
GET /purchase/orders/{purchaseOrderId}
PUT /purchase/orders/{purchaseOrderId}
POST /purchase/orders/{purchaseOrderId}/submit
POST /purchase/orders/{purchaseOrderId}/approve
POST /purchase/orders/{purchaseOrderId}/cancel
```

供应商和供货产品查询需要 `supplier:query` 权限，维护 MVP 阶段复用 `purchase:create` 权限；采购订单查询需要 `purchase:query` 权限，创建、编辑、提交、取消需要 `purchase:create` 权限，审核需要审核/审批权限。详情接口必须返回完整主表信息和全部明细，供提交/审核前预览使用。

## 19. 销售业务模块：客户与销售订单

### 19.1 页面范围

销售业务模块包含 `/sales/customers` 客户管理和 `/sales/orders` 销售订单两个页面。两个页面复用全站列表页结构：单条摘要栏、紧凑筛选区、固定列宽表格、弹窗表单、详情弹窗和受控状态动作。

### 19.2 页面与数据边界

- 客户管理字段来自 `customer`；销售订单主表字段来自 `sales_order`，销售明细来自 `sales_order_item`，产品和仓库仅作为创建/编辑时的关联选择来源。
- 查询区必须使用独立字段参数：客户编码、客户名称、联系人、销售单号、客户、仓库和状态分别提交，不提供跨字段 `keyword`。状态筛选是 `GET` 查询参数，不为单个状态查询设计 `POST`。
- 客户编码、销售单号、客户/仓库/产品快照、金额、库存锁定时间、创建人、提交时间、审核人和审计字段由后端维护，前端只读展示，不进入提交 DTO。
- 客户新增/编辑只提交客户名称、联系人、联系电话、地址、信用额度、状态和备注；客户停用后不能用于新建销售订单，历史订单继续保留客户快照。
- 销售订单列表列宽按数据含义分配：销售单号、客户、出库仓库、状态、订单金额、预计发货、库存锁定、更新时间和操作必须分列；字段较多时允许横向滚动，不把客户、仓库、状态或操作混在同一列。
- 销售订单新增/编辑弹窗只在单头维护 `expectedDeliveryDate`，标签显示为“预计发货”；明细只维护产品、销售数量、销售价和明细备注。
- 销售明细数量精度必须来自产品档案 `quantityPrecision`；价格使用 `product.reference_sale_price` 作为默认销售价，提交值仍为纯数值。
- `DRAFT` 销售单允许销售创建权限编辑；`SUBMITTED` 销售单仍可由具备审核/审批权限的人编辑客户、出库仓库、预计发货、备注和明细；`APPROVED`、`PARTIAL_OUTBOUND`、`OUTBOUND_DONE`、`CANCELLED` 不允许直接编辑。
- 提交和审核不能在列表上直接执行。点击“提交”或“审核”时必须先打开销售单详情弹窗，展示完整单头和全部销售明细；详情底部提供对应动作按钮，再弹出二次确认。预计发货为空时，详情底部显示明确提示并禁用提交/审核按钮。
- 销售订单提交或审核时后端必须重新校验客户、仓库、产品、数量精度、价格和可用库存。提交阶段可锁定库存；审核阶段如尚未锁定库存，必须在同一事务内完成锁定。
- 审核通过只生成仓库模块 `SALES_OUT` 待确认出库单，不直接扣减库存；仓库确认出库后再扣减 `warehouse_stock.stock_qty`、释放 `locked_qty`，并回写 `sales_order_item.outbound_qty`。
- 客户和销售订单都是分页列表，顶部摘要只展示当前页业务指标，不展示当前页记录数，也不得把“全筛选范围统计”放进摘要栏。

### 19.3 接口

```text
GET /sales/customers
POST /sales/customers
GET /sales/customers/options
GET /sales/customers/{customerId}
PUT /sales/customers/{customerId}
PATCH /sales/customers/{customerId}/status
PATCH /sales/customers/batch/status
POST /sales/customers/batch/delete
GET /sales/orders
POST /sales/orders
GET /sales/orders/{salesOrderId}
PUT /sales/orders/{salesOrderId}
POST /sales/orders/{salesOrderId}/submit
POST /sales/orders/{salesOrderId}/approve
POST /sales/orders/{salesOrderId}/cancel
```

客户和销售订单查询需要 `sales:query` 权限；客户维护、销售订单创建、编辑、提交和取消需要 `sales:create` 权限；审核需要审核/审批权限。详情接口必须返回完整主表信息和全部明细，供提交/审核前预览使用。
