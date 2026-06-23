package com.qiheng.erp.common.dto;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

// erp-common/dto/PageQuery.java
@Data
public class PageQuery {

    @Min(value = 1, message = "页码必须大于0")
    @NotNull(message = "页码不能为空")
    private Integer pageNum = 1;

    @Min(value = 1, message = "每页条数必须大于0")
    @Max(value = 100, message = "每页最多100条")
    @NotNull(message = "每页条数不能为空")
    private Integer pageSize = 10;

    /**
     * 转换方法,转成 MyBatis-Plus 的 Page 对象
     * @return
     * @param <T>
     */
    public <T> Page<T> toPage() {
        return new Page<>(this.pageNum, this.pageSize);
    }
}