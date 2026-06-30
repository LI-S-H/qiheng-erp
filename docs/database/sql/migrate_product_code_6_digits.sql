-- ============================================================
-- 已初始化数据变迁：系统权限模块 + 产品中心模块
-- 数据库：erp（MySQL 8）
--
-- 使用场景：
-- 1. 已经执行过系统权限模块种子数据。
-- 2. 已经执行过产品中心模块种子数据，且历史产品编码可能是 P0001 这种 4 位序号。
-- 3. 采购、销售、仓储等业务模块尚未初始化，因此本脚本不更新业务单据或库存快照表。
--
-- 变更内容：
-- 1. 兜底写入产品中心所需权限码，避免只初始化系统权限后缺少产品菜单权限。
-- 2. 将产品中心 product.product_code 从 P0001 迁移为 P000001，统一为 6 位序号。
--
-- 注意：
-- 本脚本只处理 MySQL 数据。由于后端产品编码序列来自 Redis key `product:code`，
-- 执行本脚本后请同步将 Redis 的 product:code 设置为当前最大产品序号，
-- 例如当前种子最大编码为 P000045，则将 product:code 设置为 45，避免新建产品撞码。
-- ============================================================

USE erp;

START TRANSACTION;

-- 系统权限模块：确保产品中心权限码存在。已存在时只刷新名称、模块、操作类型和说明。
INSERT INTO sys_permission (
    id,
    permission_code,
    permission_name,
    module_code,
    action_type,
    status,
    sort_order,
    description,
    create_time,
    update_time,
    deleted
) VALUES
    (1900000000000002005, 'product:query',  '产品查询', 'product', 'query',  1, 50, '查看产品与分类信息', NOW(), NOW(), 0),
    (1900000000000002006, 'product:manage', '产品维护', 'product', 'manage', 1, 60, '维护产品与分类信息', NOW(), NOW(), 0)
ON DUPLICATE KEY UPDATE
    permission_name = VALUES(permission_name),
    module_code = VALUES(module_code),
    action_type = VALUES(action_type),
    status = VALUES(status),
    sort_order = VALUES(sort_order),
    description = VALUES(description),
    update_time = NOW(),
    deleted = 0;

-- 产品中心模块：只迁移产品主数据编码，不处理尚未初始化的业务快照表。
UPDATE product
SET product_code = CONCAT('P', LPAD(CAST(SUBSTRING(product_code, 2) AS UNSIGNED), 6, '0'))
WHERE product_code REGEXP '^P[0-9]{4}$';

COMMIT;

-- 迁移后检查：
-- SELECT product_code FROM product WHERE product_code REGEXP '^P[0-9]{4}$';
-- SELECT MAX(CAST(SUBSTRING(product_code, 2) AS UNSIGNED)) AS max_product_code_seq FROM product WHERE product_code REGEXP '^P[0-9]{6}$';
