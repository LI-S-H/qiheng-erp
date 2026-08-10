package com.qiheng.erp.sales.domain.customer.dto;

import com.qiheng.erp.common.dto.PageQuery;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * <p>
 * 客户分页查询请求 DTO
 * </p>
 *
 * @author Li
 * @since 2026-08-10
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "客户分页查询请求")
public class CustomerPageDto extends PageQuery {

    @Schema(description = "客户编码（模糊查询）")
    @Size(max = 64, message = "客户编码长度不能超过64个字符")
    private String customerCode;

    @Schema(description = "客户名称（模糊查询）")
    @Size(max = 200, message = "客户名称长度不能超过200个字符")
    private String customerName;

    @Schema(description = "联系人（模糊查询）")
    @Size(max = 100, message = "联系人长度不能超过100个字符")
    private String contactName;

    @Schema(description = "状态：1启用，0禁用")
    private Integer status;
}
