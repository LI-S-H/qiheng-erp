# 工作台铃铛回归验收清单(2026-09-08)

本次合并了通知铃铛接口,顶栏铃铛数据完全复用 `GET /dashboard/overview`,所有用例以下列回归点为准。

## 数据契约

- [ ] `GET /dashboard/overview` 响应包含 `pendingCount`(整数,>=0)
- [ ] `pendingCount === todos[].count` 求和(过滤 null)
- [ ] 铃铛不再调用 `GET /dashboard/notifications`(后端接口 404)
- [ ] `DashboardTodoItemVO extends DashboardTodoSummaryVO` 反射校验通过
- [ ] 前端 `DashboardTodoSummary` 与 `DashboardTodoItem` 继承关系一致

## 铃铛渲染

- [ ] 登录后顶栏 mount,铃铛图标渲染(`store.overview` 已就绪)
- [ ] 徽标数字 = `overview.pendingCount`,>99 显示 `99+`,为 0 不渲染徽标
- [ ] 弹层列表项 = `overview.todos` 前 8 条,按 `sortWeight` 升序
- [ ] DENIED 权限(`access.todos.state === 'DENIED'`)时整个铃铛图标隐藏
- [ ] HIDDEN/EMPTY 时铃铛图标显示但弹层显示"暂无待处理事项"

## 铃铛交互

- [ ] 点击条目 → `router.push({ path: '/dashboard', query: { todoId } })`
- [ ] 工作台 mount 时读取 `route.query.todoId`,命中 `todos` 后打开详情弹窗
- [ ] 详情关闭(`Escape` 或关闭按钮)→ URL 清除 `todoId` query
- [ ] 无效 `todoId` → toast 提示 + URL 清除 `todoId`
- [ ] 弹层底部"前往工作台"按钮跳转 `/dashboard`(无 `todoId`)

## 刷新策略

- [ ] Pinia store 60s 节流:同会话内反复打开铃铛、visibilitychange 不重复请求
- [ ] 工作台 mount、刷新按钮、错误态重试 → `refresh(true)` 绕过节流
- [ ] 路由切回工作台(`path` 变化)→ 工作台重新 mount,触发 `refresh(true)`

## 动画与 a11y

- [ ] 弹层打开/关闭 opacity + scale 过渡
- [ ] 详情弹窗打开有进入动画(从右下角滑入或缩放)
- [ ] focus trap:Tab 键焦点在弹层内循环
- [ ] 铃铛 `aria-label` 完整:`待处理通知` / `待处理通知，当前有 N 项`

## 失败兜底

- [ ] overview 加载失败 → 顶栏铃铛图标不渲染,工作台显示错误态 + toast
- [ ] overview 加载失败 → 不单独设计铃铛错误态(要错一起错)

## 自动化覆盖

- 后端 JUnit:`DashboardOverviewServiceImplTest`(`shouldAccumulatePendingCountFromTodos`、`shouldReturnZeroPendingCountWhenNoTodos`、`shouldExposePendingCountFieldForBellReuse`、`todoItemShouldExtendSummaryVo`)
- 前端 smoke:`scripts/smoke-dashboard-bell.cjs`(主流程 + 无效 todoId + 底部 CTA)
- 旧 `scripts/smoke-dashboard-notifications.cjs` 删除
