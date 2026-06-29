USE erp;

START TRANSACTION;

UPDATE product
SET product_code = CONCAT('P', LPAD(CAST(SUBSTRING(product_code, 2) AS UNSIGNED), 6, '0'))
WHERE product_code REGEXP '^P[0-9]{4}$';

COMMIT;
