-- 来源改造后补充日志说明空间；请求原因上限仍为500字，不截断合法人工原因。
ALTER TABLE supplier_score_change_log
    MODIFY COLUMN reason VARCHAR(600) NOT NULL DEFAULT '' COMMENT '人工调整原因或系统计算说明；请求原因最多500字，额外保留校正说明';
