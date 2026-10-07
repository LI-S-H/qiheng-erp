-- 供应商评分最终模型直切迁移。
-- 历史直切步骤：后续按顺序执行查询补丁、migrate_supplier_score_log_sources.sql；来源数组改造后的库不要重新直切。
--
-- 适用范围：已经执行过 004_mvp_purchase.sql 的 MySQL 8.0+ 数据库。
-- 执行前必须完成可恢复备份；本脚本会清除前端直接维护的旧自动评分，
-- 删除废弃指标和供应产品级交付分，不能与旧版应用并行运行。

USE erp;

DELIMITER $$

DROP PROCEDURE IF EXISTS migrate_supplier_score_direct_cutover$$

CREATE PROCEDURE migrate_supplier_score_direct_cutover()
BEGIN
    DECLARE table_count INT DEFAULT 0;
    DECLARE column_count INT DEFAULT 0;
    DECLARE index_count INT DEFAULT 0;
    DECLARE bad_score_count BIGINT DEFAULT 0;
    DECLARE missing_fact_count BIGINT DEFAULT 0;
    DECLARE ambiguous_source_count BIGINT DEFAULT 0;
    DECLARE should_reset_legacy_scores TINYINT DEFAULT 0;

    SELECT COUNT(*) INTO table_count
      FROM information_schema.tables
     WHERE table_schema = DATABASE()
       AND table_name IN ('supplier', 'supplier_product', 'purchase_order', 'purchase_order_item', 'inbound_bill', 'inbound_bill_item', 'stock_bill', 'stock_bill_item');
    IF table_count <> 8 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = '供应商评分迁移缺少必要业务表，已中止';
    END IF;

    -- 仅在直切完成标记不存在时清空旧静态分数。该标记在来源校验、清空完成后立即写入，
    -- 能覆盖“旧列已删但首次迁移尚未完成”的 MySQL DDL 断点场景。
    SELECT COUNT(*) INTO column_count
      FROM information_schema.table_constraints
     WHERE table_schema = DATABASE()
       AND table_name = 'supplier'
       AND constraint_name = 'chk_supplier_score_direct_cutover_complete';
    IF column_count = 0 THEN
        SET should_reset_legacy_scores = 1;
    END IF;

    -- 旧分数本来就是 INT ×100；范围异常说明数据来源不可信，不能静默清空后继续迁移。
    SELECT COUNT(*) INTO bad_score_count
      FROM supplier
     WHERE overall_score NOT BETWEEN 0 AND 10000
        OR delivery_score NOT BETWEEN 0 AND 10000
        OR quality_score NOT BETWEEN 0 AND 10000
        OR price_score NOT BETWEEN 0 AND 10000
        OR service_score NOT BETWEEN 0 AND 10000;
    IF bad_score_count > 0 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'supplier 存在不在 0~10000 范围内的旧分数，已中止迁移';
    END IF;

    SELECT COUNT(*) INTO column_count
      FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'supplier_product' AND column_name = 'delivery_score';
    IF column_count = 1 THEN
        SELECT COUNT(*) INTO bad_score_count
          FROM supplier_product
         WHERE delivery_score NOT BETWEEN 0 AND 10000
            OR quality_score NOT BETWEEN 0 AND 10000
            OR price_score NOT BETWEEN 0 AND 10000
            OR ai_score NOT BETWEEN 0 AND 10000;
    ELSE
        SELECT COUNT(*) INTO bad_score_count
          FROM supplier_product
         WHERE quality_score NOT BETWEEN 0 AND 10000
            OR price_score NOT BETWEEN 0 AND 10000
            OR ai_score NOT BETWEEN 0 AND 10000;
    END IF;
    IF bad_score_count > 0 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'supplier_product 存在不在 0~10000 范围内的旧分数，已中止迁移';
    END IF;

    -- supplier：自动分允许为空；旧准时率、合格率不再保留。
    SELECT COUNT(*) INTO column_count
      FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'supplier' AND column_name = 'score_basis_amount';
    IF column_count = 0 THEN
        ALTER TABLE supplier
            ADD COLUMN score_basis_amount BIGINT NOT NULL DEFAULT 0 COMMENT '最近180天有效已确认入库金额，单位分' AFTER avg_delivery_days;
    END IF;

    SELECT COUNT(*) INTO column_count
      FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'supplier' AND column_name = 'score_status';
    IF column_count = 0 THEN
        ALTER TABLE supplier
            ADD COLUMN score_status VARCHAR(16) NOT NULL DEFAULT 'NOT_READY' COMMENT '评分状态：NOT_READY样本不足、READY可参与推荐' AFTER score_basis_amount;
    END IF;

    SELECT COUNT(*) INTO column_count
      FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'supplier' AND column_name IN ('on_time_rate', 'qualified_rate');
    IF column_count = 2 THEN
        ALTER TABLE supplier
            DROP COLUMN on_time_rate,
            DROP COLUMN qualified_rate;
    ELSEIF column_count <> 0 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'supplier 废弃指标列状态不完整，已中止迁移';
    END IF;

    SELECT COUNT(*) INTO column_count
      FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'supplier' AND column_name = 'service_score_reason';
    IF column_count = 0 THEN
        ALTER TABLE supplier
            ADD COLUMN service_score_reason VARCHAR(500) NOT NULL DEFAULT '' COMMENT '当前人工服务评分原因；服务分为空时为空字符串' AFTER service_score;
    END IF;

    ALTER TABLE supplier
        MODIFY COLUMN overall_score INT NULL DEFAULT NULL COMMENT '供应商综合评分，INT×100，NULL表示样本不足',
        MODIFY COLUMN delivery_score INT NULL DEFAULT NULL COMMENT '供应商交付评分，INT×100，NULL表示样本不足',
        MODIFY COLUMN quality_score INT NULL DEFAULT NULL COMMENT '供应商质量评分，INT×100，NULL表示样本不足',
        MODIFY COLUMN price_score INT NULL DEFAULT NULL COMMENT '供应商价格评分，INT×100，NULL表示样本不足',
        MODIFY COLUMN service_score INT NULL DEFAULT NULL COMMENT '人工服务评分，INT×100，NULL表示尚未人工设定',
        MODIFY COLUMN service_score_reason VARCHAR(500) NOT NULL DEFAULT '' COMMENT '当前人工服务评分原因；服务分为空时为空字符串',
        MODIFY COLUMN avg_delivery_days DECIMAL(10,2) NULL DEFAULT NULL COMMENT '最近180天完全入库订单的金额加权平均到货周期（天）';

    -- supplier_product：交付评分只属于供应商维度，报价与评分样本属于供货关系维度。
    SELECT COUNT(*) INTO column_count
      FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'supplier_product' AND column_name = 'quoted_purchase_price';
    IF column_count = 0 THEN
        ALTER TABLE supplier_product
            ADD COLUMN quoted_purchase_price BIGINT NULL DEFAULT NULL COMMENT '人工有效报价，单位分' AFTER latest_purchase_price;
    END IF;

    SELECT COUNT(*) INTO column_count
      FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'supplier_product' AND column_name = 'quoted_price_reason';
    IF column_count = 0 THEN
        ALTER TABLE supplier_product
            ADD COLUMN quoted_price_reason VARCHAR(500) NOT NULL DEFAULT '' COMMENT '当前人工有效报价原因；报价为空时为空字符串' AFTER quoted_purchase_price;
    END IF;

    SELECT COUNT(*) INTO column_count
      FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'supplier_product' AND column_name = 'quoted_price_updated_at';
    IF column_count = 0 THEN
        ALTER TABLE supplier_product
            ADD COLUMN quoted_price_updated_at DATETIME NULL DEFAULT NULL COMMENT '报价最近维护时间' AFTER quoted_price_reason;
    END IF;

    SELECT COUNT(*) INTO column_count
      FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'supplier_product' AND column_name = 'quote_valid_until';
    IF column_count = 0 THEN
        ALTER TABLE supplier_product
            ADD COLUMN quote_valid_until DATE NULL DEFAULT NULL COMMENT '报价有效截止日' AFTER quoted_price_updated_at;
    END IF;

    SELECT COUNT(*) INTO column_count
      FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'supplier_product' AND column_name = 'score_basis_amount';
    IF column_count = 0 THEN
        ALTER TABLE supplier_product
            ADD COLUMN score_basis_amount BIGINT NOT NULL DEFAULT 0 COMMENT '最近180天有效已确认入库金额，单位分' AFTER last_purchase_at;
    END IF;

    SELECT COUNT(*) INTO column_count
      FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'supplier_product' AND column_name = 'avg_delivery_days';
    IF column_count = 0 THEN
        ALTER TABLE supplier_product
            ADD COLUMN avg_delivery_days DECIMAL(10,2) NULL DEFAULT NULL COMMENT '最近180天完全入库采购单的金额加权平均到货周期（天），仅供分析' AFTER last_purchase_at;
    END IF;

    SELECT COUNT(*) INTO column_count
      FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'supplier_product' AND column_name = 'score_status';
    IF column_count = 0 THEN
        ALTER TABLE supplier_product
            ADD COLUMN score_status VARCHAR(16) NOT NULL DEFAULT 'NOT_READY' COMMENT '评分状态：NOT_READY样本不足、READY可参与推荐' AFTER score_basis_amount;
    END IF;

    SELECT COUNT(*) INTO column_count
      FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'supplier_product' AND column_name = 'delivery_score';
    IF column_count = 1 THEN
        ALTER TABLE supplier_product DROP COLUMN delivery_score;
    END IF;

    -- 供货关系只关联产品主数据；产品编码不得由人工重复维护。
    SELECT COUNT(*) INTO column_count
      FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'supplier_product' AND column_name = 'supplier_product_code';
    IF column_count = 1 THEN
        ALTER TABLE supplier_product DROP COLUMN supplier_product_code;
    END IF;

    -- 平均到货周期由完全入库事实回写，禁止保留人工预计交期。
    SELECT COUNT(*) INTO column_count
      FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'supplier_product' AND column_name = 'lead_time_days';
    IF column_count = 1 THEN
        ALTER TABLE supplier_product DROP COLUMN lead_time_days;
    END IF;

    ALTER TABLE supplier_product
        MODIFY COLUMN latest_purchase_price BIGINT NULL DEFAULT NULL COMMENT '由已确认入库事实维护的最近成交单价，单位分',
        MODIFY COLUMN quoted_purchase_price BIGINT NULL DEFAULT NULL COMMENT '人工有效报价，单位分',
        MODIFY COLUMN quoted_price_reason VARCHAR(500) NOT NULL DEFAULT '' COMMENT '当前人工有效报价原因；报价为空时为空字符串',
        MODIFY COLUMN quoted_price_updated_at DATETIME NULL DEFAULT NULL COMMENT '报价最近维护时间',
        MODIFY COLUMN quote_valid_until DATE NULL DEFAULT NULL COMMENT '报价有效截止日',
        MODIFY COLUMN quality_score INT NULL DEFAULT NULL COMMENT '供应产品质量评分，INT×100，NULL表示样本不足',
        MODIFY COLUMN price_score INT NULL DEFAULT NULL COMMENT '供应产品价格评分，INT×100，NULL表示样本不足',
        MODIFY COLUMN ai_score INT NULL DEFAULT NULL COMMENT '供应产品推荐评分，INT×100，NULL表示样本不足',
        MODIFY COLUMN last_purchase_at DATETIME NULL DEFAULT NULL COMMENT '当前最近成交价对应的最后一次已确认采购入库时间';

    -- 采购订单与明细：完整到货时间及供应商责任取消信息均由后续业务链路维护。
    SELECT COUNT(*) INTO column_count
      FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'purchase_order' AND column_name = 'fully_received_at';
    IF column_count = 0 THEN
        ALTER TABLE purchase_order
            ADD COLUMN fully_received_at DATETIME NULL DEFAULT NULL COMMENT '使整张采购单完全入库的最后一笔入库确认时间' AFTER approved_at;
    END IF;

    SELECT COUNT(*) INTO column_count
      FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'purchase_order' AND column_name = 'cancelled_at';
    IF column_count = 0 THEN
        ALTER TABLE purchase_order
            ADD COLUMN cancelled_at DATETIME NULL DEFAULT NULL COMMENT '取消时间' AFTER fully_received_at;
    END IF;

    SELECT COUNT(*) INTO column_count
      FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'purchase_order' AND column_name = 'cancel_reason';
    IF column_count = 0 THEN
        ALTER TABLE purchase_order
            ADD COLUMN cancel_reason VARCHAR(500) NOT NULL DEFAULT '' COMMENT '取消原因' AFTER cancelled_at;
    END IF;

    SELECT COUNT(*) INTO column_count
      FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'purchase_order' AND column_name = 'cancel_affects_delivery_score';
    IF column_count = 0 THEN
        ALTER TABLE purchase_order
            ADD COLUMN cancel_affects_delivery_score TINYINT NOT NULL DEFAULT 0 COMMENT '是否由供应商责任取消并在到期后按交付零分处理：1是，0否' AFTER cancel_reason;
    END IF;

    SELECT COUNT(*) INTO column_count
      FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'purchase_order' AND column_name = 'cancelled_by_id';
    IF column_count = 0 THEN
        ALTER TABLE purchase_order
            ADD COLUMN cancelled_by_id BIGINT NULL DEFAULT NULL COMMENT '取消操作人ID' AFTER cancel_affects_delivery_score;
    END IF;

    SELECT COUNT(*) INTO column_count
      FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'purchase_order' AND column_name = 'cancelled_by_name';
    IF column_count = 0 THEN
        ALTER TABLE purchase_order
            ADD COLUMN cancelled_by_name VARCHAR(100) NOT NULL DEFAULT '' COMMENT '取消操作人名称' AFTER cancelled_by_id;
    END IF;

    ALTER TABLE purchase_order_item
        MODIFY COLUMN selected_supplier_score INT NULL DEFAULT NULL COMMENT '下单时由后端写入的供应产品推荐分快照，INT×100，NULL表示当时不可计算';

    -- 评分变化只记录一次指标变动，同时携带受影响的产品推荐分和供应商总分前后值。
    CREATE TABLE IF NOT EXISTS supplier_score_change_log (
        id BIGINT NOT NULL COMMENT '评分变化日志ID',
        change_key CHAR(64) CHARACTER SET ascii NOT NULL COMMENT '幂等键',
        batch_no VARCHAR(64) NOT NULL COMMENT '重算批次号；人工操作也必须生成批次号',
        rule_version VARCHAR(32) NOT NULL COMMENT '评分规则版本',
        supplier_id BIGINT NOT NULL COMMENT '供应商ID',
        supplier_product_id BIGINT DEFAULT NULL COMMENT '供应商供货产品ID；供应商维度指标为空',
        metric_type VARCHAR(16) NOT NULL COMMENT '指标类型：PRICE、QUALITY、DELIVERY、SERVICE',
        metric_score_before INT DEFAULT NULL COMMENT '指标变更前评分，INT×100',
        metric_score_after INT DEFAULT NULL COMMENT '指标变更后评分，INT×100',
        product_recommend_score_before INT DEFAULT NULL COMMENT '产品推荐分变更前，INT×100',
        product_recommend_score_after INT DEFAULT NULL COMMENT '产品推荐分变更后，INT×100',
        supplier_overall_score_before INT DEFAULT NULL COMMENT '供应商综合分变更前，INT×100',
        supplier_overall_score_after INT DEFAULT NULL COMMENT '供应商综合分变更后，INT×100',
        trigger_type VARCHAR(32) NOT NULL COMMENT '触发来源：PRICE_TRIGGER、SERVICE_TRIGGER、INBOUND_TRIGGER、QUOTE_EXPIRED_TRIGGER、DAILY_TRIGGER、MERGED',
        related_business_id BIGINT DEFAULT NULL COMMENT '关联业务主键',
        related_business_no VARCHAR(64) NOT NULL DEFAULT '' COMMENT '关联业务单号',
        operator_type VARCHAR(16) NOT NULL DEFAULT 'USER' COMMENT '操作人类型：USER人工操作、SYSTEM系统任务',
        operator_id BIGINT DEFAULT NULL COMMENT '操作人ID；系统任务为空',
        operator_name VARCHAR(100) NOT NULL DEFAULT '' COMMENT '操作人名称；系统任务为空',
        reason VARCHAR(500) NOT NULL DEFAULT '' COMMENT '人工调整原因或系统计算说明',
        create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
        PRIMARY KEY (id),
        UNIQUE KEY uk_supplier_score_change_key (change_key),
        KEY idx_supplier_score_log_supplier_time (supplier_id, create_time DESC),
        KEY idx_supplier_score_log_product_time (supplier_product_id, create_time DESC),
        KEY idx_supplier_score_log_time (create_time DESC, id DESC),
        KEY idx_supplier_score_log_batch (batch_no)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='供应商评分变化日志';

    -- 早期迁移生成的日志表缺少 operator_type，重跑时补齐。
    SELECT COUNT(*) INTO column_count FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'supplier_score_change_log' AND column_name = 'operator_type';
    IF column_count = 0 THEN
        ALTER TABLE supplier_score_change_log
            ADD COLUMN operator_type VARCHAR(16) NOT NULL DEFAULT 'USER'
                COMMENT '操作人类型：USER人工操作、SYSTEM系统任务' AFTER related_business_no;
    END IF;

    SELECT COUNT(*) INTO index_count FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'supplier_score_change_log' AND index_name = 'idx_supplier_score_log_time';
    IF index_count = 0 THEN
        ALTER TABLE supplier_score_change_log
            ADD KEY idx_supplier_score_log_time (create_time DESC, id DESC);
    END IF;

    -- 评分查询、到期订单和库存事实聚合索引。
    SELECT COUNT(*) INTO index_count
      FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'supplier' AND index_name = 'idx_supplier_status_score';
    IF index_count > 0 THEN
        ALTER TABLE supplier DROP INDEX idx_supplier_status_score;
    END IF;

    SELECT COUNT(*) INTO index_count
      FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'supplier' AND index_name = 'idx_supplier_score_ready';
    IF index_count = 0 THEN
        ALTER TABLE supplier ADD KEY idx_supplier_score_ready (deleted, status, score_status, overall_score DESC);
    END IF;

    SELECT COUNT(*) INTO index_count
      FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'supplier_product' AND index_name = 'idx_supplier_product_score';
    IF index_count > 0 THEN
        ALTER TABLE supplier_product DROP INDEX idx_supplier_product_score;
    END IF;

    SELECT COUNT(*) INTO index_count
      FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'supplier_product' AND index_name = 'idx_supplier_product_ready';
    IF index_count = 0 THEN
        ALTER TABLE supplier_product ADD KEY idx_supplier_product_ready (product_id, deleted, status, score_status, ai_score DESC);
    END IF;

    SELECT COUNT(*) INTO index_count
      FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'purchase_order' AND index_name = 'idx_purchase_order_supplier_due';
    IF index_count = 0 THEN
        ALTER TABLE purchase_order ADD KEY idx_purchase_order_supplier_due (supplier_id, deleted, expected_arrival_date, status);
    END IF;

    SELECT COUNT(*) INTO index_count
      FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'purchase_order' AND index_name = 'idx_purchase_order_supplier_received';
    IF index_count = 0 THEN
        ALTER TABLE purchase_order ADD KEY idx_purchase_order_supplier_received (supplier_id, deleted, status, fully_received_at);
    END IF;

    SELECT COUNT(*) INTO index_count
      FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'supplier_product' AND index_name = 'idx_supplier_product_quote_valid';
    IF index_count = 0 THEN
        ALTER TABLE supplier_product ADD KEY idx_supplier_product_quote_valid (product_id, deleted, status, quote_valid_until);
    END IF;

    SELECT COUNT(*) INTO index_count
      FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'stock_bill_item' AND index_name = 'idx_stock_bill_item_source_bill';
    IF index_count = 0 THEN
        ALTER TABLE stock_bill_item ADD KEY idx_stock_bill_item_source_bill (business_source_item_id, bill_id);
    END IF;

    -- 采购入库事实必须能追溯到采购明细。历史种子按单号和产品一对一回填，
    -- 任何歧义或缺失都中止迁移，避免把质量、交付和成交价归属到错误供货关系。
    UPDATE inbound_bill inbound_bill
    INNER JOIN purchase_order purchase_order
        ON purchase_order.purchase_no = inbound_bill.source_no
       AND purchase_order.deleted = 0
       SET inbound_bill.source_id = purchase_order.id
     WHERE inbound_bill.inbound_type = 'PURCHASE_IN'
       AND inbound_bill.source_id IS NULL;

    SELECT COUNT(*) INTO ambiguous_source_count
      FROM (
          SELECT inbound_bill_item.id
            FROM inbound_bill_item inbound_bill_item
            INNER JOIN inbound_bill inbound_bill
                ON inbound_bill.id = inbound_bill_item.inbound_bill_id
            INNER JOIN purchase_order_item purchase_order_item
                ON purchase_order_item.purchase_order_id = inbound_bill.source_id
               AND purchase_order_item.product_id = inbound_bill_item.product_id
           WHERE inbound_bill.inbound_type = 'PURCHASE_IN'
             AND inbound_bill.source_id IS NOT NULL
             AND inbound_bill_item.source_item_id IS NULL
           GROUP BY inbound_bill_item.id
          HAVING COUNT(purchase_order_item.id) <> 1
      ) AS ambiguous_inbound_source;
    IF ambiguous_source_count > 0 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = '采购入库明细无法唯一关联采购订单明细，已中止迁移';
    END IF;

    UPDATE inbound_bill_item inbound_bill_item
    INNER JOIN inbound_bill inbound_bill
        ON inbound_bill.id = inbound_bill_item.inbound_bill_id
    INNER JOIN purchase_order_item purchase_order_item
        ON purchase_order_item.purchase_order_id = inbound_bill.source_id
       AND purchase_order_item.product_id = inbound_bill_item.product_id
       SET inbound_bill_item.source_item_id = purchase_order_item.id
     WHERE inbound_bill.inbound_type = 'PURCHASE_IN'
       AND inbound_bill.source_id IS NOT NULL
       AND inbound_bill_item.source_item_id IS NULL;

    SELECT COUNT(*) INTO ambiguous_source_count
      FROM (
          SELECT stock_bill_item.id
            FROM stock_bill_item stock_bill_item
            INNER JOIN stock_bill stock_bill
                ON stock_bill.id = stock_bill_item.bill_id
            INNER JOIN purchase_order_item purchase_order_item
                ON purchase_order_item.purchase_order_id = stock_bill.business_source_id
               AND purchase_order_item.product_id = stock_bill_item.product_id
           WHERE stock_bill.bill_type = 'PURCHASE_IN'
             AND stock_bill.business_source_id IS NOT NULL
             AND stock_bill_item.business_source_item_id IS NULL
           GROUP BY stock_bill_item.id
          HAVING COUNT(purchase_order_item.id) <> 1
      ) AS ambiguous_stock_source;
    IF ambiguous_source_count > 0 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = '采购库存流水明细无法唯一关联采购订单明细，已中止迁移';
    END IF;

    UPDATE stock_bill_item stock_bill_item
    INNER JOIN stock_bill stock_bill
        ON stock_bill.id = stock_bill_item.bill_id
    INNER JOIN purchase_order_item purchase_order_item
        ON purchase_order_item.purchase_order_id = stock_bill.business_source_id
       AND purchase_order_item.product_id = stock_bill_item.product_id
       SET stock_bill_item.business_source_item_id = purchase_order_item.id
     WHERE stock_bill.bill_type = 'PURCHASE_IN'
       AND stock_bill.business_source_id IS NOT NULL
       AND stock_bill_item.business_source_item_id IS NULL;

    SELECT COUNT(*) INTO missing_fact_count
      FROM inbound_bill_item inbound_bill_item
      INNER JOIN inbound_bill inbound_bill
        ON inbound_bill.id = inbound_bill_item.inbound_bill_id
      INNER JOIN purchase_order purchase_order
        ON purchase_order.id = inbound_bill.source_id
     WHERE inbound_bill.inbound_type = 'PURCHASE_IN'
       AND inbound_bill_item.source_item_id IS NULL;
    IF missing_fact_count > 0 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = '采购入库明细仍缺少采购订单明细来源，已中止迁移';
    END IF;

    SELECT COUNT(*) INTO missing_fact_count
      FROM stock_bill_item stock_bill_item
      INNER JOIN stock_bill stock_bill
        ON stock_bill.id = stock_bill_item.bill_id
      INNER JOIN purchase_order purchase_order
        ON purchase_order.id = stock_bill.business_source_id
     WHERE stock_bill.bill_type = 'PURCHASE_IN'
       AND stock_bill_item.business_source_item_id IS NULL;
    IF missing_fact_count > 0 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = '采购库存流水明细仍缺少采购订单明细来源，已中止迁移';
    END IF;

    IF should_reset_legacy_scores = 1 THEN
        -- 直切后旧的前端静态分数、推荐快照和手工最近成交价均不作为新体系初始值。
        UPDATE supplier
           SET overall_score = NULL,
               delivery_score = NULL,
               quality_score = NULL,
               price_score = NULL,
               service_score = NULL,
               service_score_reason = '',
               avg_delivery_days = NULL,
               score_basis_amount = 0,
               score_status = 'NOT_READY';

        UPDATE supplier_product
           SET latest_purchase_price = NULL,
               quality_score = NULL,
               price_score = NULL,
               ai_score = NULL,
               last_purchase_at = NULL,
               quoted_purchase_price = NULL,
               quoted_price_reason = '',
               quoted_price_updated_at = NULL,
               quote_valid_until = NULL,
               score_basis_amount = 0,
               score_status = 'NOT_READY';

        UPDATE purchase_order_item
           SET selected_supplier_score = NULL;

        -- 这是“数据已按新模型初始化”的持久完成标记，而不是业务字段。
        -- 标记存在时重跑迁移只能补结构，绝不再覆盖后续评分、报价或人工服务分。
        ALTER TABLE supplier ADD CONSTRAINT chk_supplier_score_direct_cutover_complete CHECK (1 = 1);
    END IF;

    -- 终态字段约束：NULL 表示未知，不允许再用 0 伪造“无样本”。
    SELECT COUNT(*) INTO column_count FROM information_schema.table_constraints
     WHERE table_schema = DATABASE() AND table_name = 'supplier' AND constraint_name = 'chk_supplier_score_range';
    IF column_count = 0 THEN
        ALTER TABLE supplier ADD CONSTRAINT chk_supplier_score_range CHECK (
            (overall_score IS NULL OR overall_score BETWEEN 0 AND 10000)
            AND (delivery_score IS NULL OR delivery_score BETWEEN 0 AND 10000)
            AND (quality_score IS NULL OR quality_score BETWEEN 0 AND 10000)
            AND (price_score IS NULL OR price_score BETWEEN 0 AND 10000)
            AND (service_score IS NULL OR service_score BETWEEN 0 AND 10000)
        );
    END IF;

    SELECT COUNT(*) INTO column_count FROM information_schema.table_constraints
     WHERE table_schema = DATABASE() AND table_name = 'supplier' AND constraint_name = 'chk_supplier_score_basis_amount';
    IF column_count = 0 THEN
        ALTER TABLE supplier ADD CONSTRAINT chk_supplier_score_basis_amount CHECK (score_basis_amount >= 0);
    END IF;

    SELECT COUNT(*) INTO column_count FROM information_schema.table_constraints
     WHERE table_schema = DATABASE() AND table_name = 'supplier' AND constraint_name = 'chk_supplier_service_score_reason';
    IF column_count = 0 THEN
        ALTER TABLE supplier ADD CONSTRAINT chk_supplier_service_score_reason CHECK (
            (service_score IS NULL AND service_score_reason = '')
            OR (service_score IS NOT NULL AND CHAR_LENGTH(TRIM(service_score_reason)) > 0)
        );
    END IF;

    SELECT COUNT(*) INTO column_count FROM information_schema.table_constraints
     WHERE table_schema = DATABASE() AND table_name = 'supplier' AND constraint_name = 'chk_supplier_score_status';
    IF column_count = 0 THEN
        ALTER TABLE supplier ADD CONSTRAINT chk_supplier_score_status CHECK (score_status IN ('NOT_READY', 'READY'));
    END IF;

    SELECT COUNT(*) INTO column_count FROM information_schema.table_constraints
     WHERE table_schema = DATABASE() AND table_name = 'supplier_product' AND constraint_name = 'chk_supplier_product_score_range';
    IF column_count = 0 THEN
        ALTER TABLE supplier_product ADD CONSTRAINT chk_supplier_product_score_range CHECK (
            (quality_score IS NULL OR quality_score BETWEEN 0 AND 10000)
            AND (price_score IS NULL OR price_score BETWEEN 0 AND 10000)
            AND (ai_score IS NULL OR ai_score BETWEEN 0 AND 10000)
        );
    END IF;

    SELECT COUNT(*) INTO column_count FROM information_schema.table_constraints
     WHERE table_schema = DATABASE() AND table_name = 'supplier_product' AND constraint_name = 'chk_supplier_product_score_basis_amount';
    IF column_count = 0 THEN
        ALTER TABLE supplier_product ADD CONSTRAINT chk_supplier_product_score_basis_amount CHECK (score_basis_amount >= 0);
    END IF;

    SELECT COUNT(*) INTO column_count FROM information_schema.table_constraints
     WHERE table_schema = DATABASE() AND table_name = 'supplier_product' AND constraint_name = 'chk_supplier_product_score_status';
    IF column_count = 0 THEN
        ALTER TABLE supplier_product ADD CONSTRAINT chk_supplier_product_score_status CHECK (score_status IN ('NOT_READY', 'READY'));
    END IF;

    SELECT COUNT(*) INTO column_count FROM information_schema.table_constraints
     WHERE table_schema = DATABASE() AND table_name = 'supplier_product' AND constraint_name = 'chk_supplier_product_quote';
    IF column_count = 1 THEN
        ALTER TABLE supplier_product DROP CHECK chk_supplier_product_quote;
    END IF;
    ALTER TABLE supplier_product ADD CONSTRAINT chk_supplier_product_quote CHECK (
        (quoted_purchase_price IS NULL AND quoted_price_reason = '' AND quoted_price_updated_at IS NULL AND quote_valid_until IS NULL)
        OR (quoted_purchase_price > 0 AND CHAR_LENGTH(TRIM(quoted_price_reason)) > 0 AND quoted_price_updated_at IS NOT NULL AND quote_valid_until IS NOT NULL)
    );

    SELECT COUNT(*) INTO column_count FROM information_schema.table_constraints
     WHERE table_schema = DATABASE() AND table_name = 'purchase_order' AND constraint_name = 'chk_purchase_order_cancel_affects_score';
    IF column_count = 0 THEN
        ALTER TABLE purchase_order ADD CONSTRAINT chk_purchase_order_cancel_affects_score CHECK (cancel_affects_delivery_score IN (0, 1));
    END IF;

    SELECT COUNT(*) INTO column_count FROM information_schema.table_constraints
     WHERE table_schema = DATABASE() AND table_name = 'purchase_order_item' AND constraint_name = 'chk_purchase_order_item_selected_supplier_score';
    IF column_count = 0 THEN
        ALTER TABLE purchase_order_item ADD CONSTRAINT chk_purchase_order_item_selected_supplier_score CHECK (
            selected_supplier_score IS NULL OR selected_supplier_score BETWEEN 0 AND 10000
        );
    END IF;

    SELECT COUNT(*) INTO column_count FROM information_schema.table_constraints
     WHERE table_schema = DATABASE() AND table_name = 'supplier_score_change_log' AND constraint_name = 'chk_supplier_score_log_metric_type';
    IF column_count = 0 THEN
        ALTER TABLE supplier_score_change_log ADD CONSTRAINT chk_supplier_score_log_metric_type CHECK (
            metric_type IN ('PRICE', 'QUALITY', 'DELIVERY', 'SERVICE')
        );
    END IF;

    SELECT COUNT(*) INTO column_count FROM information_schema.table_constraints
     WHERE table_schema = DATABASE() AND table_name = 'supplier_score_change_log' AND constraint_name = 'chk_supplier_score_log_operator_type';
    IF column_count = 0 THEN
        ALTER TABLE supplier_score_change_log ADD CONSTRAINT chk_supplier_score_log_operator_type CHECK (
            operator_type IN ('USER', 'SYSTEM')
        );
    END IF;

    SELECT COUNT(*) INTO column_count FROM information_schema.table_constraints
     WHERE table_schema = DATABASE() AND table_name = 'supplier_score_change_log' AND constraint_name = 'chk_supplier_score_log_score_range';
    IF column_count = 0 THEN
        ALTER TABLE supplier_score_change_log ADD CONSTRAINT chk_supplier_score_log_score_range CHECK (
            (metric_score_before IS NULL OR metric_score_before BETWEEN 0 AND 10000)
            AND (metric_score_after IS NULL OR metric_score_after BETWEEN 0 AND 10000)
            AND (product_recommend_score_before IS NULL OR product_recommend_score_before BETWEEN 0 AND 10000)
            AND (product_recommend_score_after IS NULL OR product_recommend_score_after BETWEEN 0 AND 10000)
            AND (supplier_overall_score_before IS NULL OR supplier_overall_score_before BETWEEN 0 AND 10000)
            AND (supplier_overall_score_after IS NULL OR supplier_overall_score_after BETWEEN 0 AND 10000)
        );
    END IF;
END$$

CALL migrate_supplier_score_direct_cutover()$$
DROP PROCEDURE migrate_supplier_score_direct_cutover$$

DELIMITER ;
