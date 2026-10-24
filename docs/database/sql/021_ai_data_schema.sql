-- AI 可用数据表的元数据登记表：表、列、表间关系。
-- 用途：受控取数底座按当前登录用户的权限码算出可见表清单，再把表结构、业务口径与
--       表间关系组装成 Schema 文本交给模型生成 SQL。模型能看到的表就是这一份，没有第二份。
--
-- 为什么要落库而不是写在配置文件里：
--   ERP 业务表会持续增加（库中 31 张，其中业务表 20 张，只有这 20 张登记开放）。
--   表清单写死在配置里意味着每加一张业务表都要改代码、发版，开放数据表的动作无法独立于
--   发布节奏。落在库里则新增业务表只需插一行数据。
--
-- 三类字段分工明确，不要混：
--   1) 结构字段（column_type / nullable / primary_key / unique_key / index_defs）：
--      来自 information_schema 的事实，可由同步程序自动刷新，不接受人工修改。
--   2) 语义字段（business_name / description / enum_values / cardinality）：
--      库表结构里没有的业务知识，只能人工维护，同步程序一律不覆盖。
--   3) 开关字段：三张表统一只有 status 一个，语义统一为「这条登记当前是否生效」。
--      没有第二个并存的开关——一个概念一个字段，避免两个字段表达同一件事。
--
-- 关于表间关系：ERP 全部 DDL 没有外键约束，关系无法从库结构推导，只能人工声明。
--   登记时只写「固定指向」的关系。多态弱关联（如 inbound_bill.source_id 靠 source_type
--   决定指向采购单还是调整单）不登记；指向 sys_user 的 *_by_id 也不登记，因为 sys_user
--   不对 AI 开放，登记后模型会去 JOIN 一个 Schema 里不存在的表。
--
-- 新库直接使用本脚本；已有库执行一次。

USE erp;

-- 1. 表登记：AI 可用数据表的清单与业务语义
--    登记表本身就是授权范围：AI 只能查这里登记的表，不登记就接触不到。
--    因此这里不再设 enabled 开关——「AI 接触不到的表」根本不登记，而不是登记了再关掉。
--    status 是人工开关：某张表要临时下线，把 status 改成 0 即可，不必删登记。
CREATE TABLE IF NOT EXISTS ai_data_table (
    id            BIGINT      NOT NULL COMMENT '主键ID',
    table_name    VARCHAR(64) NOT NULL COMMENT '物理表名',
    business_name VARCHAR(100) NOT NULL COMMENT '业务名称，给模型看',
    module_code   VARCHAR(64) NOT NULL COMMENT '所属模块编码，对应ERP权限码前缀，如 product 对应 product:query',
    description   VARCHAR(500) NOT NULL DEFAULT '' COMMENT '表说明与口径总述，给模型看',
    parent_table  VARCHAR(64) NOT NULL DEFAULT '' COMMENT '父表（明细表指向主表）；非空表示该表是明细表，取数时自动补父表过滤',
    index_defs    JSON        NOT NULL COMMENT '索引定义数组：[{"name":"索引名","unique":true,"columns":["列名"]}]，结构字段可自动同步',
    sort_order    INT         NOT NULL DEFAULT 0 COMMENT 'Schema组装顺序，主表在前',
    status        TINYINT     NOT NULL DEFAULT 1 COMMENT '开关：1生效，0停用；人工下线某张表时改这里',
    create_time   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted       TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常，1删除',
    version       INT         NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    PRIMARY KEY (id),
    UNIQUE KEY uk_ai_data_table_name (table_name),
    KEY idx_ai_data_table_module (module_code, status, deleted, sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='AI可用数据表登记表';

-- 2. 列登记：结构快照 + 业务语义
--    与表级不同，这里的登记表是「表的完整结构跟踪」，不是授权范围：
--    库里的每一列都登记（含噪声列），status 决定这一列是否下发给模型。
--    status=0 的典型是乐观锁、备注和人员操作痕迹——它们结构上存在，但对分析无意义。
--    将来某列涉及敏感信息时，把 status 改成 0 即可不用改代码。
--    结构字段（column_type / nullable / primary_key / unique_key）可由同步程序从
--    information_schema 刷新；business_name / description / enum_values 是人工维护的
--    业务语义，同步程序一律不覆盖。
CREATE TABLE IF NOT EXISTS ai_data_table_column (
    id            BIGINT      NOT NULL COMMENT '主键ID',
    table_name    VARCHAR(64) NOT NULL COMMENT '物理表名',
    column_name   VARCHAR(64) NOT NULL COMMENT '物理列名',
    column_type   VARCHAR(64) NOT NULL DEFAULT '' COMMENT '列类型，结构字段可自动同步',
    nullable      TINYINT     NOT NULL DEFAULT 1 COMMENT '是否可空，结构字段可自动同步',
    primary_key   TINYINT     NOT NULL DEFAULT 0 COMMENT '是否主键，结构字段可自动同步',
    unique_key    TINYINT     NOT NULL DEFAULT 0 COMMENT '是否唯一键，结构字段可自动同步',
    business_name VARCHAR(100) NOT NULL DEFAULT '' COMMENT '业务名称，人工维护',
    description   VARCHAR(500) NOT NULL DEFAULT '' COMMENT '口径说明：单位、放大倍数、取值含义，人工维护',
    enum_values   JSON        NOT NULL COMMENT '枚举样例，如 ["DRAFT","PENDING_APPROVAL"]，人工维护；空数组表示非枚举',
    status        TINYINT     NOT NULL DEFAULT 1 COMMENT '开关：1=下发给模型，0=不下发；乐观锁、备注、人员痕迹等噪声列保持0',
    sort_order    INT         NOT NULL DEFAULT 0 COMMENT '列顺序',
    create_time   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted       TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常，1删除',
    version       INT         NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    PRIMARY KEY (id),
    UNIQUE KEY uk_ai_data_table_column (table_name, column_name),
    KEY idx_ai_data_table_column_table (table_name, status, deleted, sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='AI可用数据表列登记表';

-- 3. 表间关系登记：ERP 无外键约束，关系只能人工声明
CREATE TABLE IF NOT EXISTS ai_data_table_relation (
    id          BIGINT      NOT NULL COMMENT '主键ID',
    from_table  VARCHAR(64) NOT NULL COMMENT '来源表',
    from_column VARCHAR(64) NOT NULL COMMENT '来源列',
    to_table    VARCHAR(64) NOT NULL COMMENT '目标表',
    to_column   VARCHAR(64) NOT NULL COMMENT '目标列',
    cardinality VARCHAR(16) NOT NULL DEFAULT 'N:1' COMMENT '基数：1:1、N:1、1:N',
    description VARCHAR(500) NOT NULL DEFAULT '' COMMENT '关系说明，给模型看',
    status      TINYINT     NOT NULL DEFAULT 1 COMMENT '开关：1生效，0停用',
    create_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted     TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常，1删除',
    version     INT         NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    PRIMARY KEY (id),
    UNIQUE KEY uk_ai_data_table_relation (from_table, from_column, to_table, to_column),
    KEY idx_ai_data_table_relation_to (to_table, to_column, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='AI可用数据表关系登记表';
