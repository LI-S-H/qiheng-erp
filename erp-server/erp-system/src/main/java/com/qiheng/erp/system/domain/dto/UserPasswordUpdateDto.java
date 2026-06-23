package com.qiheng.erp.system.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

@Data
public class UserPasswordUpdateDto {

    @NotNull
    @Schema(description = "用户ID")
    private List<String> userIds;

    @NotNull(message = "密码不能为空")
    @Schema(description = "登录密码")
    private String password;
}
