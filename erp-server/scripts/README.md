# 四模块接口集成测试

`run-four-module-api-it.ps1` 会通过真实 HTTP 接口创建全新的产品分类、仓库、产品、供应商、供货关系和客户，然后验证采购入库、销售出库、采购退货、销售退货、库存锁定、幂等、部分执行与并发确认。

运行前要求：

- 后端已启动，且目标为隔离的本机测试环境；脚本默认拒绝非环回地址。
- 使用环境变量提供测试账号，避免将密码写入脚本或命令历史。

```powershell
$env:ERP_IT_USERNAME = 'admin'
$env:ERP_IT_PASSWORD = '你的测试密码'
.\scripts\run-four-module-api-it.ps1
```

脚本每次自动生成唯一 `IT...` 前缀，并且夹具创建失败即失败，不会回退复用已有业务数据，以便暴露主数据编码或数据约束回归。它会新增测试数据但不会自动删除；请仅在隔离库运行，并可按输出的前缀筛选后清理。

若确需指向非本机的隔离环境，必须显式确认：

```powershell
.\scripts\run-four-module-api-it.ps1 -ApiBase 'https://test.example.com' -AllowNonLoopback
```
