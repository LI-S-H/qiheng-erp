-- 已部署库迁移：来源数量仅适用于关联上游明细的工作单。
-- 调整单和线下补录没有来源计划量，必须允许这三个字段为 NULL，避免被错误当作 0 数量来源。
ALTER TABLE inbound_bill_item
    MODIFY COLUMN plan_qty BIGINT DEFAULT NULL COMMENT '来源计划数量，按100倍整数存储；线下补录或调整无来源时为空',
    MODIFY COLUMN processed_qty BIGINT DEFAULT NULL COMMENT '生成本单前累计已入库数量，按100倍整数存储；线下补录或调整无来源时为空',
    MODIFY COLUMN pending_qty BIGINT DEFAULT NULL COMMENT '确认本单后剩余未入库数量，按100倍整数存储；线下补录或调整无来源时为空';

ALTER TABLE outbound_bill_item
    MODIFY COLUMN plan_qty BIGINT DEFAULT NULL COMMENT '来源计划数量，按100倍整数存储；线下补录或调整无来源时为空',
    MODIFY COLUMN processed_qty BIGINT DEFAULT NULL COMMENT '生成本单前累计已出库数量，按100倍整数存储；线下补录或调整无来源时为空',
    MODIFY COLUMN pending_qty BIGINT DEFAULT NULL COMMENT '确认本单后剩余未出库数量，按100倍整数存储；线下补录或调整无来源时为空';
