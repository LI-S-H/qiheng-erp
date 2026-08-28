-- ============================================================
-- 工作台权限与角色回滚（V1）
-- ============================================================
-- 与 010_mvp_dashboard_permissions.sql 配套，用于回滚本次变更。
-- 不删除 dashboard:exception:query 这一行（动作低频、可保留下次直接复用），
-- 改用 status=0 禁用，需要时由 010 重新启用即可。
-- ============================================================

USE erp;

-- 1) 一线三角色回滚：移除 dashboard:notifications:query
UPDATE sys_role
   SET permission_codes = JSON_REMOVE(
         permission_codes,
         JSON_UNQUOTE(JSON_SEARCH(permission_codes, 'one',
                                  'dashboard:notifications:query'))
       ),
       update_time = NOW()
 WHERE role_code IN ('PURCHASE_STAFF', 'SALES_STAFF', 'WAREHOUSE_STAFF')
   AND status    = 1
   AND deleted   = 0;

-- 2) BUSINESS_MANAGER 回滚：移除 dashboard:exception:query
UPDATE sys_role
   SET permission_codes = JSON_REMOVE(
         permission_codes,
         JSON_UNQUOTE(JSON_SEARCH(permission_codes, 'one',
                                  'dashboard:exception:query'))
       ),
       update_time = NOW()
 WHERE role_code = 'BUSINESS_MANAGER'
   AND status    = 1
   AND deleted   = 0;

-- 3) AI_ANALYST 回滚：恢复两条 dashboard 权限（保持顺序按 001 种子数据）
UPDATE sys_role
   SET permission_codes = JSON_ARRAY_APPEND(
         IF(JSON_CONTAINS(permission_codes, JSON_QUOTE('dashboard:overview:query')),
            JSON_REMOVE(permission_codes,
                        JSON_UNQUOTE(JSON_SEARCH(permission_codes, 'one',
                                                 'dashboard:overview:query'))),
            permission_codes),
         '$',
         'dashboard:overview:query'),
       update_time = NOW()
 WHERE role_code = 'AI_ANALYST' AND status = 1 AND deleted = 0;

UPDATE sys_role
   SET permission_codes = JSON_ARRAY_APPEND(
         IF(JSON_CONTAINS(permission_codes, JSON_QUOTE('dashboard:notifications:query')),
            JSON_REMOVE(permission_codes,
                        JSON_UNQUOTE(JSON_SEARCH(permission_codes, 'one',
                                                 'dashboard:notifications:query'))),
            permission_codes),
         '$',
         'dashboard:notifications:query'),
       update_time = NOW()
 WHERE role_code = 'AI_ANALYST' AND status = 1 AND deleted = 0;

-- 4) 不变量自检（与正向迁移一致）
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

DELIMITER //
DROP PROCEDURE IF EXISTS _dashboard_perm_assert //
CREATE PROCEDURE _dashboard_perm_assert()
BEGIN
  DECLARE violation INT;
  SELECT SUM(bad) INTO violation FROM _dashboard_perm_invariant;
  IF violation > 0 THEN
    SELECT * FROM _dashboard_perm_invariant WHERE bad > 0;
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = '回滚后不变量违反：有 manage 权限的角色必须同时拥有对应 query 权限';
  END IF;
END //
DELIMITER ;

CALL _dashboard_perm_assert();
DROP PROCEDURE _dashboard_perm_assert;
DROP TEMPORARY TABLE _dashboard_perm_invariant;
