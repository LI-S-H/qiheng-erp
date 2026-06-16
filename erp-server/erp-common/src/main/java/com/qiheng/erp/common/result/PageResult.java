package com.qiheng.erp.common.result;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 分页返回对象
 */
@Data
public class PageResult<T> implements Serializable {

    //数据
    private List<T> records;
    //总数据数
    private long total;
    //当前页码
    private long pageNum;
    //每页数据数
    private long pageSize;


    public static <T> PageResult<T> of(List<T> records, long total, long pageNum, long pageSize) {
        PageResult<T> result = new PageResult<>();
        result.setRecords(records);
        result.setTotal(total);
        result.setPageNum(pageNum);
        result.setPageSize(pageSize);
        return result;
    }
}
