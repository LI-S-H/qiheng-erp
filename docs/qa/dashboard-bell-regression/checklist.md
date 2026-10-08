# 工作台铃铛回归验收清单

顶栏铃铛数据复用 `GET /dashboard/overview`。本清单中的未勾选项表示尚未在此清单记录验收结果，不代表功能一定未实现；执行回归后再逐项勾选。

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
- [x] DENIED 权限(`access.todos.state === 'DENIED'`)时铃铛仍显示，打开后展示无可见待办的空态；不得因待办权限隐藏铃铛（2026-10-08，Mock 故障注入回归）
- [ ] HIDDEN/EMPTY 时铃铛图标显示但弹层显示"当前没有待处理事项"

## 铃铛交互

- [x] 点击条目 → `router.push({ path: '/dashboard', query: { todoId } })`
- [x] 工作台读取 `route.query.todoId`,命中 `todos` 后打开详情弹窗
- [x] 详情关闭(`Escape`)→ URL 清除 `todoId` query
- [x] 无效 `todoId` → toast 提示 + URL 清除 `todoId`
- [x] 弹层底部"前往工作台"按钮跳转 `/dashboard`(无 `todoId`)，等待 Popover 退出后确认没有详情弹窗

## 刷新策略

- [x] Pinia store 60s 节流、并发请求合并和旧会话响应隔离（会话单元测试）
- [ ] 工作台 mount、刷新按钮、错误态重试 → `refresh(true)` 绕过节流
- [ ] 路由切回工作台(`path` 变化)→ 工作台重新 mount,触发 `refresh(true)`

## 动画与 a11y

- [ ] 弹层打开/关闭 opacity + scale 过渡
- [ ] 详情弹窗打开有进入动画(从右下角滑入或缩放)
- [ ] focus trap:Tab 键焦点在弹层内循环
- [ ] 铃铛 `aria-label` 完整:`待处理通知` / `待处理通知，当前有 N 项`

## 失败兜底

- [ ] 首次 overview 加载失败且无旧数据 → 顶栏不展示旧账号铃铛数据，工作台显示错误态 + toast
- [x] 后台刷新失败且有旧数据 → 保留上次概览，记录 `refreshError`，不会清空待办（会话单元测试）
- [ ] 后台刷新失败 → 顶栏提示“刷新失败，当前显示上次数据”，工作台显示同源提示；真实故障场景仍需单独验收

## 自动化覆盖

- 后端 JUnit:`DashboardOverviewServiceImplTest`(`shouldAccumulatePendingCountFromTodos`、`shouldReturnZeroPendingCountWhenNoTodos`、`shouldExposePendingCountFieldForBellReuse`、`todoItemShouldExtendSummaryVo`)
- 前端 smoke:`scripts/smoke-dashboard-bell.cjs`(主流程 + 无效 todoId + 底部 CTA)
- 会话单元：`scripts/test-dashboard-session.cjs`。2026-10-08 本轮通过情况见[修复验证报告](../2026-10-08-remaining-fixes.md)，未勾选项没有冒充完整产品验收。
- 旧 `scripts/smoke-dashboard-notifications.cjs` 删除
