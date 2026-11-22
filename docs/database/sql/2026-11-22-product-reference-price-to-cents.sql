-- 产品参考价由元（decimal）改为分（bigint），与交易金额统一口径。
-- 执行前备份 product 表；执行后重启后端，旧代码会把分当元，价格显示小 100 倍。
-- 只执行一次，重复执行会让价格再放大 100 倍。

UPDATE product
SET reference_purchase_price = ROUND(reference_purchase_price * 100),
    reference_sale_price = ROUND(reference_sale_price * 100);

ALTER TABLE product
    MODIFY reference_purchase_price BIGINT NOT NULL DEFAULT 0 COMMENT '参考采购价，按分存储',
    MODIFY reference_sale_price BIGINT NOT NULL DEFAULT 0 COMMENT '参考销售价，按分存储';

-- 校验：典型商品价格应为原元值乘 100
-- SELECT product_code, reference_purchase_price, reference_sale_price FROM product LIMIT 5;