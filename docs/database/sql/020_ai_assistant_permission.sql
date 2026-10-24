-- 权限收敛：AI 助手页面统一使用 ai:assistant:use。
-- 旧的分项权限码（库存/销售/采购问答、运维建议、决策建议）不再使用，从权限目录和角色授权中移除。
-- 执行前建议备份 sys_permission 与 sys_role；本脚本包含删除操作。

USE erp;

-- 1. 新增统一入口权限
--    001_mvp_system_permission.sql 已经把这一行作为基础种子，这里保留是为了修补只跑过旧种子的库。
--    注意用 1900000000000002015：旧的 1900000000000002020 已被 system:dept:manage 占用，
--    沿用会让 INSERT IGNORE 静默跳过，目录里就查不到这条权限码。
INSERT IGNORE INTO sys_permission
  (id, permission_code, permission_name, module_code, action_type, status, sort_order,
   description, create_time, update_time, deleted, version)
VALUES
  (1900000000000002015, 'ai:assistant:use', 'AI 智能助手', 'ai', 'execute', 1, 200,
   '进入 AI 智能助手页面并使用其全部功能', NOW(), NOW(), 0, 0);

-- 2. 角色授权替换：移除旧 ai 权限码，追加新权限码。
--    只处理原本引用过 ai 权限、且还没有新权限码的角色，重复执行不会把新权限码追加两次；
--    通配角色 ["*"] 不受影响。
UPDATE sys_role r
JOIN (
    SELECT r2.id, JSON_ARRAYAGG(jt.code) AS kept_codes
    FROM sys_role r2,
         JSON_TABLE(r2.permission_codes, '$[*]' COLUMNS (code VARCHAR(100) PATH '$')) jt
    WHERE r2.deleted = 0
      AND jt.code NOT IN ('ai:query:stock', 'ai:query:sales', 'ai:query:purchase',
                          'ai:ops:suggest', 'ai:decision:suggest')
    GROUP BY r2.id
) kept ON kept.id = r.id
SET r.permission_codes = JSON_ARRAY_APPEND(kept.kept_codes, '$', 'ai:assistant:use')
WHERE r.deleted = 0
  AND JSON_SEARCH(r.permission_codes, 'one', 'ai:%') IS NOT NULL
  AND JSON_SEARCH(r.permission_codes, 'one', 'ai:assistant:use') IS NULL;

-- 3. 删除旧权限码
DELETE FROM sys_permission
WHERE permission_code IN ('ai:query:stock', 'ai:query:sales', 'ai:query:purchase',
                          'ai:ops:suggest', 'ai:decision:suggest');
