-- 归一化历史遗留的退货原因编码。
--
-- 背景：ReturnReasonCode 枚举发布时规范编码是 QUALITY_ISSUE，但历史库曾写入 QUALITY。
--   代码侧已由 ReturnReasonCode.normalizeStoredCode 做读取归一化（QUALITY -> QUALITY_ISSUE，
--   未知旧值降级为 OTHER），但库里仍留着旧值。直接按 reason_code = 'QUALITY_ISSUE' 过滤查询
--   会漏掉这些行，统计口径与页面展示对不上。
--
-- 处理：把残留的 QUALITY 统一改写为规范编码 QUALITY_ISSUE。
--   reason_code 只在 return_order 一张表上，不涉及其它单据表；
--   逻辑删除的行也一并改写，避免将来恢复时旧值重新出现。
--
-- 影响面：只改这一个编码值，不动金额、数量、时间与状态；
--   改完后 return_order 的 reason_code 全部落在 ReturnReasonCode 的七个规范值内。
--
-- 幂等：重复执行没有可改的行，结果相同。

USE erp;

UPDATE return_order
SET reason_code = 'QUALITY_ISSUE'
WHERE reason_code = 'QUALITY';
