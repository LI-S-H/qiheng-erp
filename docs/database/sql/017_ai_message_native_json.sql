-- 开发库一次性切换：先停止 AI 请求并备份 ai_message；不保留双写或兼容读取。
-- 015 是旧 TEXT -> blocks 格式的历史迁移；已有 blocks 库只执行本脚本，新库使用 006。
-- DDL 隐式提交；执行器必须检查警告，并在 UPDATE 后逐条核验再提交，不要盲目重跑。
USE erp;

-- 无法无损转换时先失败，不能覆盖 metadata 同名值或丢掉未知内容块。
CREATE TEMPORARY TABLE ai_message_native_guard (valid TINYINT NOT NULL CHECK (valid=1));
INSERT INTO ai_message_native_guard
SELECT IF(EXISTS (
    SELECT 1 FROM ai_message AS m
    WHERE COALESCE(JSON_EXTRACT(m.content,'$.version'),0)<>1
       OR COALESCE(JSON_TYPE(JSON_EXTRACT(m.content,'$.blocks')),'NULL')<>'ARRAY'
       OR m.role NOT IN ('user','assistant','tool')
       OR EXISTS (SELECT 1 FROM JSON_TABLE(m.content,'$.blocks[*]' COLUMNS(
           block_type VARCHAR(16) PATH '$.type',body JSON PATH '$.text' NULL ON EMPTY)) AS b
           WHERE b.block_type IS NULL OR b.block_type NOT IN ('text','chart')
              OR (b.block_type='text' AND COALESCE(JSON_TYPE(b.body),'NULL')<>'STRING'))
       OR EXISTS (SELECT 1 FROM JSON_TABLE(JSON_KEYS(COALESCE(JSON_EXTRACT(m.content,'$.metadata'),JSON_OBJECT())),
           '$[*]' COLUMNS(field_name VARCHAR(255) PATH '$')) AS k
           WHERE k.field_name NOT IN ('app','modelMetadata')
             AND JSON_CONTAINS_PATH(m.content,'one',CONCAT('$.metadata.modelMetadata.',k.field_name)))
       OR JSON_CONTAINS_PATH(m.content,'one','$.metadata.charts')
),0,1);
DROP TEMPORARY TABLE ai_message_native_guard;

ALTER TABLE ai_message
    MODIFY COLUMN id BIGINT NOT NULL COMMENT '消息ID',
    MODIFY COLUMN conversation_id BIGINT NOT NULL COMMENT '会话ID',
    MODIFY COLUMN user_id BIGINT NOT NULL COMMENT '所属用户ID，用于所有权校验',
    MODIFY COLUMN content LONGTEXT NOT NULL COMMENT 'Jackson完整框架消息JSON原文，避免原生JSON数字精度转换',
    ADD COLUMN run_status VARCHAR(32) DEFAULT NULL COMMENT '轮次状态，仅USER_INPUT使用，不改写消息原文' AFTER content,
    ADD COLUMN error_code VARCHAR(80) DEFAULT NULL COMMENT '轮次失败原因编码，仅USER_INPUT使用' AFTER run_status,
    MODIFY COLUMN create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    MODIFY COLUMN update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    MODIFY COLUMN deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常，1删除',
    ADD CONSTRAINT chk_ai_message_content CHECK (JSON_VALID(content)),
    COMMENT='AI经营助手消息表';

-- 上限与数据库实际包大小一致；执行器拒绝截断警告，不默默截掉多文本块原文。
SET SESSION group_concat_max_len = @@max_allowed_packet;

-- 先搬状态，后转换原文；旧模型元数据与展示扩展保留在同一 metadata 内。
UPDATE ai_message
SET run_status = CASE WHEN kind='USER_INPUT' THEN NULLIF(JSON_UNQUOTE(JSON_EXTRACT(content,'$.metadata.app.run.status')),'null') ELSE NULL END,
    error_code = CASE WHEN kind='USER_INPUT' THEN NULLIF(JSON_UNQUOTE(JSON_EXTRACT(content,'$.metadata.app.run.errorCode')),'null') ELSE NULL END,
    update_time=update_time;

-- 提示直接作用于本条语句；即使测试执行器每条 SQL 使用新连接，也不会退回默认1024字节。
UPDATE /*+ SET_VAR(group_concat_max_len=1073741824) */ ai_message AS m
SET m.content = JSON_MERGE_PRESERVE(
    JSON_OBJECT(
        'messageType',UPPER(m.role),
        'text',COALESCE((SELECT GROUP_CONCAT(b.body ORDER BY b.ord SEPARATOR '\n')
            FROM JSON_TABLE(m.content,'$.blocks[*]' COLUMNS(ord FOR ORDINALITY,
                block_type VARCHAR(16) PATH '$.type',body LONGTEXT PATH '$.text' NULL ON EMPTY)) AS b
            WHERE b.block_type='text'),''),
        'metadata',JSON_MERGE_PRESERVE(
            JSON_SET(COALESCE(JSON_EXTRACT(m.content,'$.metadata.modelMetadata'),JSON_OBJECT()),'$.messageType',UPPER(m.role)),
            JSON_REMOVE(COALESCE(JSON_EXTRACT(m.content,'$.metadata'),JSON_OBJECT()),'$.modelMetadata','$.app'),
            IF(JSON_CONTAINS_PATH(m.content,'one','$.blocks[*].spec'),
                JSON_OBJECT('charts',COALESCE((SELECT JSON_ARRAYAGG(c.spec)
                    OVER (ORDER BY c.ord ROWS BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING)
                    FROM JSON_TABLE(m.content,'$.blocks[*]' COLUMNS(ord FOR ORDINALITY,
                        block_type VARCHAR(16) PATH '$.type',spec JSON PATH '$.spec' NULL ON EMPTY)) AS c
                    WHERE c.block_type='chart' LIMIT 1),JSON_ARRAY())),JSON_OBJECT()))),
    CASE m.role
        WHEN 'assistant' THEN JSON_OBJECT('toolCalls',COALESCE(JSON_EXTRACT(m.content,'$.toolCalls'),JSON_ARRAY()),'media',JSON_ARRAY())
        WHEN 'tool' THEN JSON_OBJECT('responses',COALESCE(JSON_EXTRACT(m.content,'$.toolResponses'),JSON_ARRAY()))
        ELSE JSON_OBJECT('media',JSON_ARRAY())
    END),
    m.update_time=m.update_time;
