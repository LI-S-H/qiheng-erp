-- ============================================================
-- 工作台权限与角色调整（V1）
-- ============================================================
-- 适配版本：erp-server 1.0.0-SNAPSHOT（2026-08-29）
--
-- 本迁移由 commit 2 提交，配合 commit 1（refactor）与
-- commit 3（feat）一起上线。
--
-- 变更内容：
--   1) 新增 dashboard:exception:query 权限码
--   2) PURCHASE_STAFF / SALES_STAFF / WAREHOUSE_STAFF 各自新增
--      dashboard:notifications:query，让一线岗位能够查看顶栏铃铛
--      自己的待办摘要
--   3) BUSINESS_MANAGER 角色追加 dashboard:exception:query
--   4) AI_ANALYST 角色移除两条 dashboard 权限（角色保留启用状态）
--   5) 不变量自检："有 manage 必有 query"，违反则阻断迁移
--
-- 回滚：执行 docs/database/sql/011_rollback_dashboard_permissions.sql
-- ============================================================

USE erp;

-- 1) 新增 dashboard:exception:query 权限码
INSERT INTO sys_permission
  (id, permission_code, permission_name, module_code, action_type,
   status, sort_order, description, create_time, update_time, deleted, version)
VALUES
  (1900000000000002092, 'dashboard:exception:query', '工作台系统异常摘要',
   'dashboard', 'query', 1, 148,
   '查看工作台聚合的系统异常待办，仅返回条目摘要与代表异常，不代替异常中心处理',
   NOW(), NOW(), 0, 0)
ON DUPLICATE KEY UPDATE
  permission_name = VALUES(permission_name),
  module_code     = VALUES(module_code),
  action_type     = VALUES(action_type),
  sort_order      = VALUES(sort_order),
  description     = VALUES(description),
  update_time     = NOW();

-- 2) 一线三角色各自新增 dashboard:notifications:query
UPDATE sys_role
   SET permission_codes = JSON_ARRAY_APPEND(permission_codes, '$',
                                            'dashboard:notifications:query'),
       update_time      = NOW()
 WHERE role_code IN ('PURCHASE_STAFF', 'SALES_STAFF', 'WAREHOUSE_STAFF')
   AND status  = 1
   AND deleted = 0
   AND NOT JSON_CONTAINS(
         permission_codes, JSON_QUOTE('dashboard:notifications:query')
       );

-- 3) BUSINESS_MANAGER 追加 dashboard:exception:query
UPDATE sys_role
   SET permission_codes = JSON_ARRAY_APPEND(permission_codes, '$',
                                            'dashboard:exception:query'),
       update_time      = NOW()
 WHERE role_code = 'BUSINESS_MANAGER'
   AND status    = 1
   AND deleted   = 0
   AND NOT JSON_CONTAINS(
         permission_codes, JSON_QUOTE('dashboard:exception:query')
       );

-- 4) AI_ANALYST 移除两条 dashboard 权限（角色保留启用）
--    拆成两次独立 UPDATE 避免 JSON_REMOVE 在同一 SET 子句中下标漂移。
UPDATE sys_role
   SET permission_codes = JSON_REMOVE(
         permission_codes,
         JSON_UNQUOTE(JSON_SEARCH(permission_codes, 'one',
                                  'dashboard:overview:query'))
       ),
       update_time = NOW()
 WHERE role_code = 'AI_ANALYST'
   AND status    = 1
   AND deleted   = 0
   AND JSON_CONTAINS(permission_codes, JSON_QUOTE('dashboard:overview:query'));

UPDATE sys_role
   SET permission_codes = JSON_REMOVE(
         permission_codes,
         JSON_UNQUOTE(JSON_SEARCH(permission_codes, 'one',
                                  'dashboard:notifications:query'))
       ),
       update_time = NOW()
 WHERE role_code = 'AI_ANALYST'
   AND status    = 1
   AND deleted   = 0
   AND JSON_CONTAINS(permission_codes, JSON_QUOTE('dashboard:notifications:query'));

-- 5) 不变量自检："有 manage 必有 query"
--    下述任何一个违反都会让本迁移失败（执行器应当捕获 SELECT 的非 0 输出）
DROP TEMPORARY TABLE IF EXISTS _dashboard_perm_invariant;
CREATE TEMPORARY TABLE _dashboard_perm_invariant (
  pair VARCHAR(64) NOT NULL,
  bad  INT NOT NULL
);

INSERT INTO _dashboard_perm_invariant (pair, bad)
SELECT 'purchase:manage↔query',
       COUNT(*)
  FROM sys_role
 WHERE status = 1 AND deleted = 0
   AND JSON_CONTAINS(permission_codes, JSON_QUOTE('purchase:manage'))
   AND NOT JSON_CONTAINS(permission_codes, JSON_QUOTE('purchase:query'))
UNION ALL
SELECT 'sales:manage↔query',
       COUNT(*)
  FROM sys_role
 WHERE status = 1 AND deleted = 0
   AND JSON_CONTAINS(permission_codes, JSON_QUOTE('sales:manage'))
   AND NOT JSON_CONTAINS(permission_codes, JSON_QUOTE('sales:query'))
UNION ALL
SELECT 'warehouse:manage↔query',
       COUNT(*)
  FROM sys_role
 WHERE status = 1 AND deleted = 0
   AND JSON_CONTAINS(permission_codes, JSON_QUOTE('warehouse:manage'))
   AND NOT JSON_CONTAINS(permission_codes, JSON_QUOTE('warehouse:query'))
UNION ALL
SELECT 'supplier:manage↔query',
       COUNT(*)
  FROM sys_role
 WHERE status = 1 AND deleted = 0
   AND JSON_CONTAINS(permission_codes, JSON_QUOTE('supplier:manage'))
   AND NOT JSON_CONTAINS(permission_codes, JSON_QUOTE('supplier:query'))
UNION ALL
SELECT 'customer:manage↔query',
       COUNT(*)
  FROM sys_role
 WHERE status = 1 AND deleted = 0
   AND JSON_CONTAINS(permission_codes, JSON_QUOTE('customer:manage'))
   AND NOT JSON_CONTAINS(permission_codes, JSON_QUOTE('customer:query'))
UNION ALL
SELECT 'product:manage↔query',
       COUNT(*)
  FROM sys_role
 WHERE status = 1 AND deleted = 0
   AND JSON_CONTAINS(permission_codes, JSON_QUOTE('product:manage'))
   AND NOT JSON_CONTAINS(permission_codes, JSON_QUOTE('product:query'));

-- 只要存在任何一对违反不变量，则抛错阻塞迁移。
-- 运维需先核对角色权限数据再重新执行本脚本。
DELIMITER //
DROP PROCEDURE IF EXISTS _dashboard_perm_assert //
CREATE PROCEDURE _dashboard_perm_assert()
BEGIN
  DECLARE violation INT;
  SELECT SUM(bad) INTO violation FROM _dashboard_perm_invariant;
  IF violation > 0 THEN
    SELECT * FROM _dashboard_perm_invariant WHERE bad > 0;
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = '不变量违反：有 manage 权限的角色必须同时拥有对应 query 权限';
  END IF;
END //
DELIMITER ;

CALL _dashboard_perm_assert();
DROP PROCEDURE _dashboard_perm_assert;
DROP TEMPORARY TABLE _dashboard_perm_invariant;
