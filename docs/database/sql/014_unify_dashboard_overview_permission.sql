-- ============================================================
-- 工作台概览权限收敛
-- 适用：erp（MySQL 8+）
--
-- 顶栏铃铛复用 GET /dashboard/overview 的 pendingCount 和 todos，
-- 不再维护 dashboard:notifications:query 独立权限。
-- 执行后需要清理角色权限选项缓存，并让受影响用户重新登录以刷新权限 Session。
-- ============================================================

USE erp;
SET NAMES utf8mb4;

START TRANSACTION;

-- 1) 兼容历史角色：旧铃铛权限单独存在时，先补齐唯一的工作台概览权限。
UPDATE sys_role
   SET permission_codes = JSON_ARRAY_APPEND(permission_codes, '$', 'dashboard:overview:query'),
       update_time = NOW()
 WHERE deleted = 0
   AND JSON_CONTAINS(permission_codes, JSON_QUOTE('dashboard:notifications:query'))
   AND NOT JSON_CONTAINS(permission_codes, JSON_QUOTE('dashboard:overview:query'));

-- 2) 移除所有未删除角色中的已废弃权限码。
UPDATE sys_role
   SET permission_codes = JSON_REMOVE(
         permission_codes,
         JSON_UNQUOTE(JSON_SEARCH(permission_codes, 'one', 'dashboard:notifications:query'))
       ),
       update_time = NOW()
 WHERE deleted = 0
   AND JSON_CONTAINS(permission_codes, JSON_QUOTE('dashboard:notifications:query'));

-- 3) 统一唯一权限的名称和说明，并删除旧权限定义。
UPDATE sys_permission
   SET permission_name = '工作台概览',
       description = '查看工作台概览、首屏指标、趋势、待办、库存风险、订单流转、商品排行、供应商履约和顶栏铃铛摘要',
       update_time = NOW()
 WHERE permission_code = 'dashboard:overview:query'
   AND deleted = 0;

DELETE FROM sys_permission
 WHERE permission_code = 'dashboard:notifications:query';

-- 4) 防止角色中残留已移除的权限码，避免以后重新暴露为可配置选项。
DROP TEMPORARY TABLE IF EXISTS _dashboard_permission_cleanup;
CREATE TEMPORARY TABLE _dashboard_permission_cleanup (
  check_name VARCHAR(96) NOT NULL,
  bad_count  INT NOT NULL
);

INSERT INTO _dashboard_permission_cleanup (check_name, bad_count)
SELECT 'role contains retired dashboard notification permission', COUNT(*)
  FROM sys_role
 WHERE deleted = 0
   AND JSON_CONTAINS(permission_codes, JSON_QUOTE('dashboard:notifications:query'))
UNION ALL
SELECT 'retired dashboard notification permission still exists', COUNT(*)
  FROM sys_permission
 WHERE permission_code = 'dashboard:notifications:query'
   AND deleted = 0;

DELIMITER //
DROP PROCEDURE IF EXISTS _dashboard_permission_cleanup_assert //
CREATE PROCEDURE _dashboard_permission_cleanup_assert()
BEGIN
  DECLARE violation INT;
  SELECT SUM(bad_count) INTO violation FROM _dashboard_permission_cleanup;
  IF violation > 0 THEN
    SELECT * FROM _dashboard_permission_cleanup WHERE bad_count > 0;
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = '工作台权限收敛失败：仍存在已废弃的顶栏通知权限';
  END IF;
END //
DELIMITER ;

CALL _dashboard_permission_cleanup_assert();
DROP PROCEDURE _dashboard_permission_cleanup_assert;
DROP TEMPORARY TABLE _dashboard_permission_cleanup;

COMMIT;

-- 运维收尾：清理 Redis 中的 system:options:permissions，并使受影响用户重新登录。
