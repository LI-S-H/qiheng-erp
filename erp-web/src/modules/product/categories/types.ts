export type ProductCategoryStatus = 0 | 1;

export interface ProductCategoryListItem {
  categoryId: string;
  parentId: string;
  categoryName: string;
  status: ProductCategoryStatus;
  productCount: number;
  children?: ProductCategoryListItem[];
  createTime: string;
  updateTime: string;
}

export interface ProductCategoryQuery {
  categoryName?: string;
  status?: ProductCategoryStatus | '' | 'all';
}

export interface ProductCategoryFormPayload {
  parentId: string;
  categoryName: string;
  status: ProductCategoryStatus;
}

export interface ProductCategoryBatchIdsPayload {
  categoryIds: string[];
}

export interface ProductCategoryBatchStatusPayload extends ProductCategoryBatchIdsPayload {
  status: ProductCategoryStatus;
}
