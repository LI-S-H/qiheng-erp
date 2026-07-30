package com.qiheng.erp.warehouse.domain.common.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

@Data
@Schema(description = "分页查询响应基类")
public class PageRespVo<T> implements Serializable {

    @Schema(description = "记录列表")
    private List<T> records;

    @Schema(description = "总记录数")
    private Integer total;

    @Schema(description = "当前页码")
    private Integer pageNum;

    @Schema(description = "每页条数")
    private Integer pageSize;
}
