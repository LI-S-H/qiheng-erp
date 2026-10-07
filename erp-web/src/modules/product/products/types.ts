export type ProductStatus = 0 | 1;

export interface ProductListItem {
  productId: string;
  productCode: string;
  productName: string;
  categoryId: string | null;
  categoryName: string | null;
  brandName: string;
  unitName: string;
  quantityPrecision: number;
  specification: string;
  barcode: string | null;
  referencePurchasePrice: number;
  referenceSalePrice: number;
  safetyStockQty: number;
  status: ProductStatus;
  remark: string;
  createTime: string;
  updateTime: string;
}

export interface ProductQuery {
  productCode?: string;
  productName?: string;
  brandName?: string;
  barcode?: string;
  categoryId?: string | 'all';
  status?: ProductStatus | '' | 'all';
  pageNum: number;
  pageSize: number;
}

export interface ProductFormPayload {
  productName: string;
  categoryId: string | null;
  brandName: string;
  unitName: string;
  quantityPrecision: number;
  specification: string;
  barcode: string | null;
  // referencePurchasePrice(参考采购价)在编辑接口中不可改。
  // 初始创建时可在 ProductFormPayload 中传入(后端 ProductFormRequest 接受该字段);
  // 后续调整必须通过单独接口 PUT /products/{id}/reference-price。
  referencePurchasePrice: number;
  referenceSalePrice: number;
  safetyStockQty: number;
  status: ProductStatus;
  remark: string;
}

/**
 * 参考采购价调整请求(单独接口,2026-09-25 拆分)。
 */
export interface ProductReferencePricePayload {
  referencePurchasePrice: number;
}

export interface ProductBatchIdsPayload {
  productIds: string[];
}

export interface ProductBatchStatusPayload extends ProductBatchIdsPayload {
  status: ProductStatus;
}
