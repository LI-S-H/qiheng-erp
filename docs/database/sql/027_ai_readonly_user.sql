-- AI 取数专用只读数据库账号。
--
-- 背景：AI 智能助手会把模型生成的 SQL 送到数据库执行。这条路径必须与业务写路径分开：
--   · 业务连接用 root / erp 账号，有全部读写权限；
--   · AI 连接用这个只读账号，任何写语句在数据库层就被拒绝。
--
-- 边界说明（重要）：
--   这个账号的 SELECT 权限只解决「防写」，不解决「防越权读」。
--   账号能读整个 erp 库；「用户能查哪张表」由应用层的 SQL 守卫按权限码白名单负责。
--   两道防线互补，缺一不可。
--
-- 执行方式：用有 CREATE USER 权限的账号（通常是 root）执行本脚本。
-- 重复执行安全：账号已存在时只更新口令。
--
-- 口令请按环境实际修改；生产环境的口令由部署环境注入，不写进仓库。

USE erp;

-- 1. 建账号（已存在则跳过，重复执行不会报错）
CREATE USER IF NOT EXISTS 'erp_ai_ro'@'%' IDENTIFIED BY 'erp_ai_ro_2026';

-- 2. 更新口令（重复执行时把口令对齐到当前脚本值）
ALTER USER 'erp_ai_ro'@'%' IDENTIFIED BY 'erp_ai_ro_2026';

-- 3. 只给 SELECT，不给任何写权限、也不给 PROCESS / FILE / SUPER 等附加权限
GRANT SELECT ON erp.* TO 'erp_ai_ro'@'%';

-- 4. 确认该账号没有写权限
--    SHOW GRANTS FOR 'erp_ai_ro'@'%';
--    期望只看到一行：GRANT SELECT ON `erp`.* TO `erp_ai_ro`@`%`
