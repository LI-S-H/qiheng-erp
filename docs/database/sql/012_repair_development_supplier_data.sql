-- 本地开发库精确数据修复。
-- 适用库：erp（MySQL 8+）；执行前使用 utf8mb4 连接。
-- 仅修复已核验的乱码、采购关联、完全入库事实和基础启用供货关系的当前报价；
-- 不生成评分、评分样本金额、平均到货周期或评分变化日志。

USE erp;
SET NAMES utf8mb4;

START TRANSACTION;

-- 1. 只修正已确认的两条工作台权限乱码，避免对正常中文数据做全表转码。
UPDATE sys_permission
   SET permission_name = CASE permission_code
           WHEN 'dashboard:overview:query' THEN '工作台概览'
           WHEN 'dashboard:exception:query' THEN '工作台系统异常摘要'
       END,
       description = CASE permission_code
           WHEN 'dashboard:overview:query' THEN '查看工作台概览、首屏指标、趋势、待办、库存风险、订单流转、商品排行、供应商履约和顶栏铃铛摘要'
           WHEN 'dashboard:exception:query' THEN '查看工作台聚合的系统异常待办，仅返回条目摘要与代表异常，不代替异常中心处理'
       END
 WHERE permission_code IN (
       'dashboard:overview:query',
       'dashboard:exception:query'
 );

-- 2. 修正两个已核验的采购明细供货关系。历史单据允许引用已停用关系，不能改写为当前启用供应商。
UPDATE purchase_order_item
   SET supplier_product_id = 2011000000000000008
 WHERE purchase_no = 'PO202607005'
   AND product_code = 'P000002'
   AND supplier_product_id = 2011000000000000002;

UPDATE purchase_order_item
   SET supplier_product_id = 2011000000000000006
 WHERE purchase_no = 'PO2026073100001'
   AND product_code = 'P000026'
   AND supplier_product_id IS NULL;

-- 3. 只按已确认入库单的最后确认时间回填完全入库事实；无可追溯确认单的历史订单保持 NULL。
UPDATE purchase_order
   SET fully_received_at = CASE purchase_no
       WHEN 'PO202606001' THEN '2026-06-14 09:12:00'
       WHEN 'PO2026081500001' THEN '2026-08-15 01:53:50'
       WHEN 'PO2026081500003' THEN '2026-08-15 01:53:53'
       WHEN 'PO2026081500004' THEN '2026-08-15 15:25:50'
       WHEN 'PO2026081500006' THEN '2026-08-15 15:25:53'
   END
 WHERE purchase_no IN (
       'PO202606001',
       'PO2026081500001',
       'PO2026081500003',
       'PO2026081500004',
       'PO2026081500006'
   )
   AND status = 'INBOUND_DONE'
   AND fully_received_at IS NULL;

-- 4. 基础启用供货关系补齐当前人工报价四元组。历史成交价优先；没有成交事实时取产品参考采购价。
-- 原因明确为开发库初始数据，后续应通过报价调整接口维护；不触碰评分相关字段。
UPDATE supplier_product
   SET quoted_purchase_price = CASE id
           WHEN 2011000000000000001 THEN 3520
           WHEN 2082726653984452610 THEN 5200
           WHEN 2011000000000000002 THEN 4050
           WHEN 2011000000000000003 THEN 6800
           WHEN 2011000000000000004 THEN 5800
           WHEN 2011000000000000005 THEN 1250
           WHEN 2011000000000000006 THEN 9200
           WHEN 2088323218746720258 THEN 1234
           WHEN 2088527565099220994 THEN 1234
       END,
       quoted_price_reason = '开发库初始报价，待业务确认',
       quoted_price_updated_at = '2026-09-20 09:00:00',
       quote_valid_until = '2026-12-31'
 WHERE id IN (
       2011000000000000001,
       2082726653984452610,
       2011000000000000002,
       2011000000000000003,
       2011000000000000004,
       2011000000000000005,
       2011000000000000006,
       2088323218746720258,
       2088527565099220994
   )
   AND deleted = 0
   AND status = 1
   AND (
       (quoted_purchase_price IS NULL
        AND quoted_price_reason = ''
        AND quoted_price_updated_at IS NULL
        AND quote_valid_until IS NULL)
       -- 仅恢复曾被错误终端编码写成连续问号的同一组初始报价，不覆盖正常人工报价。
       OR (quoted_price_updated_at = '2026-09-20 09:00:00'
           AND quote_valid_until = '2026-12-31'
           AND CHAR_LENGTH(quoted_price_reason) > 0
           AND CHAR_LENGTH(REPLACE(quoted_price_reason, '?', '')) = 0)
   );

COMMIT;

-- 角色权限选项会读取 Redis 缓存；执行本脚本后需清理：
-- redis-cli -h 127.0.0.1 -p 6379 DEL system:options:permissions
