-- ERP 演示数据 v2 业务规则检查。
-- 每行第一列为检查项，第二列应为 0；非 0 表示该检查未通过。
-- 适用：已导入 demo-data-v2.sql 的隔离库；检查日期口径为 2026-11-22。
SET NAMES utf8mb4;

SELECT '商品数量' AS check_name, ABS(COUNT(*)-1560) AS errors FROM product
UNION ALL SELECT '供应商数量', ABS(COUNT(*)-200) FROM supplier
UNION ALL SELECT '客户数量', ABS(COUNT(*)-200) FROM customer
UNION ALL SELECT '仓库数量', ABS(COUNT(*)-5) FROM warehouse
UNION ALL SELECT '分类数量', ABS(COUNT(*)-126) FROM product_category WHERE deleted=0
UNION ALL SELECT '顶级分类数量', ABS(COUNT(*)-22) FROM product_category WHERE parent_id=0 AND deleted=0
UNION ALL SELECT '商品编码格式', COUNT(*) FROM product WHERE product_code NOT REGEXP '^P[0-9]{6}$' OR quantity_precision<>0
UNION ALL SELECT '商品分类层级', COUNT(*) FROM product p LEFT JOIN product_category c ON c.id=p.category_id LEFT JOIN product_category parent ON parent.id=c.parent_id WHERE c.id IS NULL OR c.deleted<>0 OR c.status<>1 OR c.parent_id=0 OR parent.id IS NULL OR parent.deleted<>0 OR parent.status<>1 OR (parent.parent_id<>0 AND NOT EXISTS (SELECT 1 FROM product_category a WHERE a.id=parent.parent_id AND a.deleted=0 AND a.status=1))
UNION ALL SELECT '商品名称不重复', COUNT(*) FROM (SELECT product_name,COUNT(*) n FROM product GROUP BY product_name HAVING COUNT(*)>1) t
UNION ALL SELECT '商品名称自然', COUNT(*) FROM product WHERE product_name LIKE '%演示%' OR product_name REGEXP '[0-9]{3}[[:space:]]*号'
UNION ALL SELECT '仓库名称自然', COUNT(*) FROM warehouse WHERE warehouse_name LIKE '%演示%'
UNION ALL SELECT '供应商名称自然', COUNT(*) FROM supplier WHERE supplier_name LIKE '%演示%' OR supplier_name REGEXP '[0-9]{3}[[:space:]]*号' OR supplier_name LIKE '%贸易贸易%' OR supplier_name LIKE '%供应链供应链%'
UNION ALL SELECT '客户名称自然', COUNT(*) FROM customer WHERE customer_name LIKE '%演示%' OR customer_name REGEXP '[0-9]{3}[[:space:]]*号'
UNION ALL SELECT '供应商联系信息', COUNT(*) FROM supplier WHERE COALESCE(TRIM(contact_name),'')='' OR COALESCE(contact_phone,'') NOT REGEXP '^1[3-9][0-9]{9}$' OR COALESCE(TRIM(address),'')=''
UNION ALL SELECT '客户联系信息', COUNT(*) FROM customer WHERE COALESCE(TRIM(contact_name),'')='' OR COALESCE(contact_phone,'') NOT REGEXP '^1[3-9][0-9]{9}$' OR COALESCE(TRIM(address),'')='' OR COALESCE(TRIM(remark),'')=''
UNION ALL SELECT '仓库联系信息', COUNT(*) FROM warehouse WHERE COALESCE(contact_phone,'') NOT REGEXP '^1[3-9][0-9]{9}$'
UNION ALL SELECT '商品价格为正', COUNT(*) FROM product WHERE reference_purchase_price<=0 OR reference_sale_price<=reference_purchase_price
UNION ALL SELECT '参考采购价多样性', IF(COUNT(DISTINCT reference_purchase_price)>=800,0,1) FROM product
UNION ALL SELECT '毛利率多样性', IF(COUNT(DISTINCT ROUND((reference_sale_price/reference_purchase_price-1)*100))>=50,0,1) FROM product
UNION ALL SELECT '安全库存多样性', IF(COUNT(DISTINCT safety_stock_qty)>=80,0,1) FROM product

-- 供货关系与评分
UNION ALL SELECT '每商品至少一条供货关系', COUNT(*) FROM product p WHERE NOT EXISTS (SELECT 1 FROM supplier_product sp WHERE sp.product_id=p.id AND sp.deleted=0 AND sp.status=1)
UNION ALL SELECT '多源商品数量', IF(COUNT(*)>=300,0,1) FROM (SELECT product_id FROM supplier_product WHERE deleted=0 AND status=1 GROUP BY product_id HAVING COUNT(*)>=2) t
UNION ALL SELECT '供货关系唯一', COUNT(*) FROM (SELECT supplier_id,product_id,COUNT(*) n FROM supplier_product GROUP BY supplier_id,product_id HAVING COUNT(*)>1) t
UNION ALL SELECT '供货报价约束', COUNT(*) FROM supplier_product sp WHERE sp.quoted_purchase_price IS NULL OR sp.quoted_purchase_price<=0 OR TRIM(sp.quoted_price_reason)='' OR sp.quoted_price_updated_at IS NULL OR sp.quote_valid_until IS NULL OR sp.min_order_qty<=0
UNION ALL SELECT '起订量多样性', IF(COUNT(DISTINCT min_order_qty)>=5,0,1) FROM supplier_product
UNION ALL SELECT '供货评分状态', COUNT(*) FROM supplier_product sp WHERE (sp.score_status='READY' AND sp.recommend_score IS NULL) OR (sp.score_status='NOT_READY' AND sp.recommend_score IS NOT NULL) OR sp.quality_score NOT BETWEEN 0 AND 10000 OR sp.price_score NOT BETWEEN 0 AND 10000 OR (sp.recommend_score IS NOT NULL AND sp.recommend_score NOT BETWEEN 0 AND 10000)
UNION ALL SELECT '供货推荐分公式', COUNT(*) FROM supplier_product sp JOIN supplier s ON s.id=sp.supplier_id WHERE sp.recommend_score IS NOT NULL AND ABS(sp.recommend_score-ROUND(sp.price_score*0.3+s.delivery_score*0.3+sp.quality_score*0.3+s.service_score*0.1,0))>1
UNION ALL SELECT '供应商综合分公式', COUNT(*) FROM supplier s WHERE s.overall_score IS NOT NULL AND ABS(s.overall_score-ROUND(s.price_score*0.3+s.delivery_score*0.3+s.quality_score*0.3+s.service_score*0.1,0))>1
UNION ALL SELECT '供应商价格分公式', COUNT(*) FROM (SELECT s.id, s.price_score, SUM(sp.score_basis_amount*sp.price_score)/SUM(sp.score_basis_amount) calc FROM supplier s JOIN supplier_product sp ON sp.supplier_id=s.id AND sp.score_basis_amount>0 AND sp.price_score IS NOT NULL GROUP BY s.id,s.price_score) t WHERE t.price_score<>ROUND(t.calc,0)
UNION ALL SELECT '供货价格分公式', COUNT(*) FROM supplier_product sp JOIN product p ON p.id=sp.product_id WHERE sp.price_score IS NOT NULL AND (sp.status<>1 OR sp.quote_valid_until<'2026-11-22' OR sp.price_score<>LEAST(10000,ROUND(p.reference_purchase_price*10000/sp.quoted_purchase_price,0)))
UNION ALL SELECT '供货质量分公式', COUNT(*) FROM supplier_product sp WHERE sp.quality_score IS NOT NULL AND sp.quality_score NOT BETWEEN 0 AND 10000
UNION ALL SELECT '供应商入库金额', COUNT(*) FROM supplier s LEFT JOIN (SELECT supplier_id,SUM(score_basis_amount) amount FROM supplier_product GROUP BY supplier_id) x ON x.supplier_id=s.id WHERE NOT (s.score_basis_amount<=>COALESCE(x.amount,0))
UNION ALL SELECT '评分状态梯度', IF(COUNT(DISTINCT delivery_score)>=10 AND COUNT(DISTINCT quality_score)>=10 AND MAX(overall_score)-MIN(overall_score)>=500,0,1) FROM supplier WHERE score_status='READY'
UNION ALL SELECT '评分样本不足样本', IF(COUNT(*)>=20,0,1) FROM supplier WHERE score_status='NOT_READY'

-- 采购订单
UNION ALL SELECT '采购单数量', IF(COUNT(*)>=3000,0,1) FROM purchase_order
UNION ALL SELECT '采购明细数量', IF(COUNT(*)>=8000,0,1) FROM purchase_order_item
UNION ALL SELECT '采购多行单占比', IF((SELECT COUNT(*) FROM (SELECT purchase_order_id FROM purchase_order_item GROUP BY purchase_order_id HAVING COUNT(*)>1) t)*100 >= (SELECT COUNT(*)*20 FROM purchase_order),0,1)
UNION ALL SELECT '采购状态多样性', IF(SUM(status='INBOUND_DONE')>=3000 AND SUM(status='APPROVED')>=50 AND SUM(status='SUBMITTED')>=5 AND SUM(status='DRAFT')>=5 AND SUM(status='CANCELLED')>=5,0,1) FROM purchase_order
UNION ALL SELECT '采购关联与金额', COUNT(*) FROM purchase_order_item i LEFT JOIN purchase_order o ON o.id=i.purchase_order_id LEFT JOIN supplier_product sp ON sp.id=i.supplier_product_id LEFT JOIN product p ON p.id=i.product_id WHERE o.id IS NULL OR sp.id IS NULL OR p.id IS NULL OR o.supplier_id<>sp.supplier_id OR sp.product_id<>i.product_id OR i.purchase_no<>o.purchase_no OR i.product_code<>p.product_code OR i.product_name<>p.product_name OR i.quantity_precision<>p.quantity_precision OR i.quantity<=0 OR i.unit_price<=0 OR i.total_amount<>i.quantity*i.unit_price/100 OR o.total_amount<>(SELECT SUM(x.total_amount) FROM purchase_order_item x WHERE x.purchase_order_id=o.id) OR (o.approved_at IS NOT NULL AND o.approved_at<o.create_time) OR (o.fully_received_at IS NOT NULL AND o.approved_at IS NOT NULL AND o.fully_received_at<o.approved_at)
UNION ALL SELECT '采购数量精度', COUNT(*) FROM purchase_order_item WHERE quantity_precision<>0 OR MOD(quantity,100)<>0 OR MOD(inbound_qty,100)<>0
UNION ALL SELECT '采购累计入库量', COUNT(*) FROM purchase_order_item poi LEFT JOIN (SELECT ibi.source_item_id,SUM(ibi.current_qty) qty FROM inbound_bill_item ibi JOIN inbound_bill ib ON ib.id=ibi.inbound_bill_id WHERE ib.inbound_type='PURCHASE_IN' AND ib.status='CONFIRMED' GROUP BY ibi.source_item_id) x ON x.source_item_id=poi.id WHERE poi.inbound_qty<>COALESCE(x.qty,0)
UNION ALL SELECT '采购状态与入库进度', COUNT(*) FROM purchase_order o WHERE (o.status='INBOUND_DONE' AND (o.fully_received_at IS NULL OR EXISTS (SELECT 1 FROM purchase_order_item i WHERE i.purchase_order_id=o.id AND i.inbound_qty<i.quantity))) OR (o.status='PARTIAL_INBOUND' AND NOT EXISTS (SELECT 1 FROM purchase_order_item i WHERE i.purchase_order_id=o.id AND i.inbound_qty>0 AND i.inbound_qty<i.quantity)) OR (o.status IN ('DRAFT','SUBMITTED','CANCELLED') AND EXISTS (SELECT 1 FROM purchase_order_item i WHERE i.purchase_order_id=o.id AND i.inbound_qty>0))
UNION ALL SELECT '采购单号格式', COUNT(*) FROM purchase_order WHERE purchase_no NOT REGEXP '^PO[0-9]{13}$' OR SUBSTRING(purchase_no,3,8)<>DATE_FORMAT(create_time,'%Y%m%d')
UNION ALL SELECT '采购逾期样本', IF(COUNT(*)>=30,0,1) FROM purchase_order WHERE status='INBOUND_DONE' AND fully_received_at IS NOT NULL AND DATE(fully_received_at)>expected_arrival_date
UNION ALL SELECT '采购月度波动', IF(MAX(c)/MIN(c)>=1.25,0,1) FROM (SELECT DATE_FORMAT(create_time,'%Y-%m') m,COUNT(*) c FROM purchase_order WHERE create_time>='2025-12-01' AND create_time<'2026-11-01' GROUP BY m) t

-- 入库单
UNION ALL SELECT '入库明细数量', IF(COUNT(*)>=8000,0,1) FROM inbound_bill_item
UNION ALL SELECT '入库关联与数量', COUNT(*) FROM inbound_bill_item i LEFT JOIN inbound_bill b ON b.id=i.inbound_bill_id LEFT JOIN purchase_order_item p ON p.id=i.source_item_id LEFT JOIN stock_bill_item s ON s.id=i.stock_bill_item_id WHERE (b.inbound_type='PURCHASE_IN' AND (p.id IS NULL OR b.source_id<>p.purchase_order_id OR b.source_no<>p.purchase_no OR i.inbound_no<>b.inbound_no OR i.product_id<>p.product_id OR i.plan_qty<>p.quantity OR i.plan_qty<>i.processed_qty+i.current_qty+i.pending_qty OR i.qualified_qty+i.defective_qty<>i.current_qty OR (b.status='CONFIRMED' AND (s.id IS NULL OR s.work_bill_item_id<>i.id OR s.change_qty<>i.current_qty)) OR (b.status<>'CONFIRMED' AND i.current_qty<>0)))
UNION ALL SELECT '入库质检数量', COUNT(*) FROM inbound_bill_item i JOIN inbound_bill b ON b.id=i.inbound_bill_id WHERE b.inbound_type IN ('PURCHASE_IN','SALES_RETURN') AND (i.qualified_qty<0 OR i.defective_qty<0 OR i.qualified_qty+i.defective_qty<>i.current_qty)
UNION ALL SELECT '入库单号格式', COUNT(*) FROM inbound_bill WHERE inbound_no NOT REGEXP '^IB[0-9]{13}$' OR SUBSTRING(inbound_no,3,8)<>DATE_FORMAT(create_time,'%Y%m%d')
UNION ALL SELECT '入库状态多样性', COUNT(*) FROM inbound_bill WHERE status NOT IN ('DRAFT','PENDING_CONFIRM','CONFIRMED','CANCELLED')
UNION ALL SELECT '待确认入库单存在', IF(COUNT(*)>=50,0,1) FROM inbound_bill WHERE status='PENDING_CONFIRM'

-- 销售订单
UNION ALL SELECT '销售单数量', IF(COUNT(*)>=3000,0,1) FROM sales_order
UNION ALL SELECT '销售明细数量', IF(COUNT(*)>=8000,0,1) FROM sales_order_item
UNION ALL SELECT '销售多行单占比', IF((SELECT COUNT(*) FROM (SELECT sales_order_id FROM sales_order_item GROUP BY sales_order_id HAVING COUNT(*)>1) t)*100 >= (SELECT COUNT(*)*20 FROM sales_order),0,1)
UNION ALL SELECT '销售状态多样性', IF(SUM(status='OUTBOUND_DONE')>=3000 AND SUM(status='PARTIAL_OUTBOUND')>=50 AND SUM(status='APPROVED')>=10 AND SUM(status='SUBMITTED')>=10 AND SUM(status='DRAFT')>=20,0,1) FROM sales_order
UNION ALL SELECT '销售关联与金额', COUNT(*) FROM sales_order_item i LEFT JOIN sales_order o ON o.id=i.sales_order_id LEFT JOIN product p ON p.id=i.product_id WHERE o.id IS NULL OR p.id IS NULL OR i.sales_no<>o.sales_no OR i.product_code<>p.product_code OR i.product_name<>p.product_name OR i.quantity_precision<>p.quantity_precision OR i.quantity<=0 OR i.unit_price<=0 OR i.total_amount<>i.quantity*i.unit_price/100 OR o.total_amount<>(SELECT SUM(x.total_amount) FROM sales_order_item x WHERE x.sales_order_id=o.id) OR (o.approved_at IS NOT NULL AND o.approved_at<o.create_time)
UNION ALL SELECT '销售锁定与出库进度', COUNT(*) FROM sales_order_item i JOIN sales_order o ON o.id=i.sales_order_id WHERE (o.status IN ('SUBMITTED','APPROVED') AND i.locked_qty<>i.quantity) OR (o.status='DRAFT' AND i.locked_qty<>0) OR (o.status='PARTIAL_OUTBOUND' AND (i.outbound_qty<0 OR i.locked_qty<>GREATEST(0,i.quantity-i.outbound_qty))) OR (o.status='OUTBOUND_DONE' AND i.outbound_qty<>i.quantity)
UNION ALL SELECT '销售累计出库量', COUNT(*) FROM sales_order_item soi LEFT JOIN (SELECT obi.source_item_id,SUM(obi.current_qty) qty FROM outbound_bill_item obi JOIN outbound_bill ob ON ob.id=obi.outbound_bill_id WHERE ob.outbound_type='SALES_OUT' AND ob.status='CONFIRMED' GROUP BY obi.source_item_id) x ON x.source_item_id=soi.id WHERE soi.outbound_qty<>COALESCE(x.qty,0)
UNION ALL SELECT '销售折扣样本', IF(COUNT(*)>=2000,0,1) FROM sales_order_item i JOIN product p ON p.id=i.product_id WHERE i.unit_price<p.reference_sale_price
UNION ALL SELECT '销售单号格式', COUNT(*) FROM sales_order WHERE sales_no NOT REGEXP '^SO[0-9]{13}$' OR SUBSTRING(sales_no,3,8)<>DATE_FORMAT(create_time,'%Y%m%d')
UNION ALL SELECT '销售预计日期合规', COUNT(*) FROM sales_order WHERE status IN ('DRAFT','SUBMITTED','APPROVED') AND (expected_delivery_date IS NULL OR expected_delivery_date<'2026-11-22')
UNION ALL SELECT '销售月度波动', IF(MAX(c)/MIN(c)>=1.15,0,1) FROM (SELECT DATE_FORMAT(create_time,'%Y-%m') m,COUNT(*) c FROM sales_order WHERE create_time>='2025-12-01' AND create_time<'2026-11-01' GROUP BY m) t

-- 出库单
UNION ALL SELECT '出库明细数量', IF(COUNT(*)>=8000,0,1) FROM outbound_bill_item
UNION ALL SELECT '出库关联与数量', COUNT(*) FROM outbound_bill_item i LEFT JOIN outbound_bill b ON b.id=i.outbound_bill_id LEFT JOIN sales_order_item s ON s.id=i.source_item_id LEFT JOIN stock_bill_item st ON st.id=i.stock_bill_item_id WHERE (b.outbound_type='SALES_OUT' AND (s.id IS NULL OR b.source_id<>s.sales_order_id OR b.source_no<>s.sales_no OR i.outbound_no<>b.outbound_no OR i.product_id<>s.product_id OR i.plan_qty<s.quantity OR i.plan_qty<>i.processed_qty+i.current_qty+i.pending_qty OR (b.status='CONFIRMED' AND (st.id IS NULL OR st.work_bill_item_id<>i.id OR st.change_qty<>-i.current_qty)) OR (b.status<>'CONFIRMED' AND i.current_qty<>0)))
UNION ALL SELECT '出库单号格式', COUNT(*) FROM outbound_bill WHERE outbound_no NOT REGEXP '^OB[0-9]{13}$' OR SUBSTRING(outbound_no,3,8)<>DATE_FORMAT(create_time,'%Y%m%d')
UNION ALL SELECT '待确认出库单存在', IF(COUNT(*)>=50,0,1) FROM outbound_bill WHERE status='PENDING_CONFIRM'

-- 库存
UNION ALL SELECT '库存余额与流水', COUNT(*) FROM warehouse_stock w LEFT JOIN (SELECT b.warehouse_id,i.product_id,SUM(i.change_qty) total FROM stock_bill b JOIN stock_bill_item i ON i.bill_id=b.id GROUP BY b.warehouse_id,i.product_id) x ON x.warehouse_id=w.warehouse_id AND x.product_id=w.product_id WHERE w.stock_qty<>COALESCE(x.total,0)
UNION ALL SELECT '库存非负', COUNT(*) FROM warehouse_stock WHERE stock_qty<0 OR locked_qty<0 OR locked_qty>stock_qty
UNION ALL SELECT '库存锁定量', COUNT(*) FROM warehouse_stock ws LEFT JOIN (SELECT warehouse_id,product_id,SUM(qty) qty FROM (SELECT so.warehouse_id,soi.product_id,CASE so.status WHEN 'PARTIAL_OUTBOUND' THEN soi.quantity-soi.outbound_qty ELSE soi.quantity END qty FROM sales_order so JOIN sales_order_item soi ON soi.sales_order_id=so.id WHERE so.status IN ('SUBMITTED','APPROVED','PARTIAL_OUTBOUND') UNION ALL SELECT ro.warehouse_id,roi.product_id,roi.approved_qty-roi.processed_qty FROM return_order ro JOIN return_order_item roi ON roi.return_order_id=ro.id WHERE ro.return_type='PURCHASE_RETURN' AND ro.status IN ('APPROVED','PARTIAL_EXECUTED')) x GROUP BY warehouse_id,product_id) l ON l.warehouse_id=ws.warehouse_id AND l.product_id=ws.product_id WHERE ws.locked_qty<>COALESCE(l.qty,0)
UNION ALL SELECT '库存流水算术', COUNT(*) FROM stock_bill_item WHERE before_qty+change_qty<>after_qty
UNION ALL SELECT '库存流水单号格式', COUNT(*) FROM stock_bill WHERE bill_no NOT REGEXP '^SL[0-9]{13}$' OR SUBSTRING(bill_no,3,8)<>DATE_FORMAT(confirmed_at,'%Y%m%d')
UNION ALL SELECT '零库存商品样本', IF(COUNT(*)>=100,0,1) FROM product p WHERE NOT EXISTS (SELECT 1 FROM warehouse_stock w WHERE w.product_id=p.id AND w.stock_qty>0)
UNION ALL SELECT '低库存商品样本', IF(COUNT(*)>=50,0,1) FROM (SELECT p.id FROM product p JOIN warehouse_stock w ON w.product_id=p.id WHERE w.warehouse_id=(SELECT MIN(w2.warehouse_id) FROM warehouse_stock w2 WHERE w2.product_id=p.id) GROUP BY p.id,p.safety_stock_qty HAVING SUM(w.stock_qty)<p.safety_stock_qty) t
UNION ALL SELECT '跨仓商品样本', IF(COUNT(*)>=200,0,1) FROM (SELECT product_id FROM warehouse_stock GROUP BY product_id HAVING COUNT(*)>1) t
UNION ALL SELECT '调拨流水存在', IF(COUNT(*)>=50,0,1) FROM stock_bill WHERE bill_type='ADJUST_OUT'
UNION ALL SELECT '积压或滞销样本', IF(COUNT(*)>=50,0,1) FROM (SELECT ws.product_id FROM warehouse_stock ws WHERE ws.stock_qty>0 AND NOT EXISTS (SELECT 1 FROM stock_bill_item i JOIN stock_bill b ON b.id=i.bill_id WHERE i.product_id=ws.product_id AND b.warehouse_id=ws.warehouse_id AND i.change_qty<0 AND b.confirmed_at>='2026-08-24') GROUP BY ws.product_id) t

-- 退货
UNION ALL SELECT '退货单数量', IF(COUNT(*)>=200,0,1) FROM return_order
UNION ALL SELECT '退货状态多样性', IF(SUM(status='DRAFT')>=80 AND SUM(status='SUBMITTED')>=20 AND SUM(status='APPROVED')>=10 AND SUM(status='PARTIAL_EXECUTED')>=20 AND SUM(status='COMPLETED')>=20 AND SUM(status='CANCELLED')>=5,0,1) FROM return_order
UNION ALL SELECT '退货类型多样性', IF(SUM(return_type='PURCHASE_RETURN')>=50 AND SUM(return_type='SALES_RETURN')>=50,0,1) FROM return_order
UNION ALL SELECT '退货来源状态', COUNT(*) FROM return_order r LEFT JOIN purchase_order p ON r.return_type='PURCHASE_RETURN' AND p.id=r.source_order_id LEFT JOIN sales_order s ON r.return_type='SALES_RETURN' AND s.id=r.source_order_id WHERE (r.return_type='PURCHASE_RETURN' AND p.status NOT IN ('PARTIAL_INBOUND','INBOUND_DONE')) OR (r.return_type='SALES_RETURN' AND s.status NOT IN ('PARTIAL_OUTBOUND','OUTBOUND_DONE')) OR NOT (r.source_order_no<=>COALESCE(p.purchase_no,s.sales_no))
UNION ALL SELECT '退货仓库一致', COUNT(*) FROM return_order r LEFT JOIN purchase_order p ON r.return_type='PURCHASE_RETURN' AND p.id=r.source_order_id LEFT JOIN sales_order s ON r.return_type='SALES_RETURN' AND s.id=r.source_order_id WHERE r.warehouse_id<>COALESCE(p.warehouse_id,s.warehouse_id)
UNION ALL SELECT '退货明细数量约束', COUNT(*) FROM return_order_item i LEFT JOIN return_order r ON r.id=i.return_order_id LEFT JOIN purchase_order_item pi ON r.return_type='PURCHASE_RETURN' AND pi.id=i.source_order_item_id LEFT JOIN sales_order_item si ON r.return_type='SALES_RETURN' AND si.id=i.source_order_item_id WHERE i.requested_qty<=0 OR i.source_fulfilled_qty<=0 OR i.requested_qty>i.source_fulfilled_qty OR i.approved_qty>i.requested_qty OR i.processed_qty>i.approved_qty OR i.total_amount<>i.requested_qty*i.unit_price/100 OR (pi.id IS NULL AND si.id IS NULL)
UNION ALL SELECT '退货金额汇总', COUNT(*) FROM return_order r WHERE r.total_amount<>(SELECT SUM(i.total_amount) FROM return_order_item i WHERE i.return_order_id=r.id)
UNION ALL SELECT '退货执行单据', COUNT(*) FROM return_order r WHERE r.status IN ('PARTIAL_EXECUTED','COMPLETED') AND NOT EXISTS (SELECT 1 FROM inbound_bill ib WHERE ib.source_type='SALES_RETURN_ORDER' AND ib.source_id=r.id AND ib.status='CONFIRMED') AND NOT EXISTS (SELECT 1 FROM outbound_bill ob WHERE ob.source_type='PURCHASE_RETURN_ORDER' AND ob.source_id=r.id AND ob.status='CONFIRMED')
UNION ALL SELECT '待执行退货单据', COUNT(*) FROM return_order r WHERE r.status='APPROVED' AND r.return_type='PURCHASE_RETURN' AND NOT EXISTS (SELECT 1 FROM outbound_bill ob WHERE ob.source_type='PURCHASE_RETURN_ORDER' AND ob.source_id=r.id AND ob.status IN ('PENDING_CONFIRM','CONFIRMED'))
UNION ALL SELECT '退货单号格式', COUNT(*) FROM return_order WHERE (return_type='PURCHASE_RETURN' AND return_no NOT REGEXP '^PR[0-9]{13}$') OR (return_type='SALES_RETURN' AND return_no NOT REGEXP '^SR[0-9]{13}$') OR SUBSTRING(return_no,3,8)<>DATE_FORMAT(create_time,'%Y%m%d');