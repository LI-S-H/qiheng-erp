# 前端页面设计思路

## 1. 前端架构选择

前端软件名为 **启衡 ERP**。

前端采用 `Vue 3 + TypeScript + Vite + Element Plus + Pinia + Vue Router + Axios`。

选择这套技术栈的原因：

- 项目是企业内部 ERP 管理系统，核心页面以表格、筛选、表单、弹窗、状态流转和操作确认为主，Element Plus 与这类场景匹配度高
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
- 页面优先使用列表页、筛选区、表单弹窗、详情抽屉、状态标签和确认弹窗，服务高频业务处理
- 视觉上避免营销页式大面积装饰，重点突出信息密度、可读性和操作确定性
- 菜单先固定展示，权限以后端接口校验为准，符合 MVP 阶段“页面入口统一展示、进入页面或查询时校验权限码”的数据库设计
- 前端不展示接口文档入口，接口文档只维护在 `docs/api/erp-openapi.yaml`，用于导入 Apifox

## 4. 权限与登录态设计

系统权限库表设计中，MVP 阶段保留：

```text
sys_dept
sys_user
sys_role
sys_user_role
```

角色权限码直接保存在 `sys_role.permission_codes`，登录后由服务端把用户基础信息、角色编码和权限码放入 Redis session。前端不自行推导权限，只保存服务端返回的当前用户上下文。

请求鉴权采用 Sa-Token 原始 token。前端登录成功后保存 token，并在 Axios 请求拦截器中通过 `satoken` 请求头传给后端。

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
- 账号关键词、部门、角色、状态筛选
- 用户分页表格
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
| 创建时间 | `sys_user.created_at` |
| 更新时间 | `sys_user.updated_at` |
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

当前页面为了便于审核，先使用页面内置的临时数据展示交互效果。

后端接口完成后，只需要把页面中的临时数据加载替换为：

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

## 8. 前端风格规范

后续页面的视觉和交互细节统一参考 `docs/frontend-style-guide.md`。

当前确认的风格方向是：整体保持简洁克制；列表页操作按钮优先使用浅色底色和轻量边框；危险操作参考用户管理页删除按钮的浅红搭配；表格行内只保留少量高频操作；批量操作放在表格工具栏；表格标签需要宽度跟随内容并让文字居中；分页区域总数偏左，页码和跳页控件居中。
