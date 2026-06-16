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
  referencePurchasePrice: number;
  referenceSalePrice: number;
  safetyStockQty: number;
  status: ProductStatus;
  remark: string;
}

export interface ProductBatchIdsPayload {
  productIds: string[];
}

export interface ProductBatchStatusPayload extends ProductBatchIdsPayload {
  status: ProductStatus;
}
