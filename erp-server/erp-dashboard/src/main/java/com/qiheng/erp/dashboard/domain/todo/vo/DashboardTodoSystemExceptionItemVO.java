package com.qiheng.erp.dashboard.domain.todo.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/** 工作台系统异常详情项。 */
@Data
@Schema(description = "工作台系统异常详情项")
public class DashboardTodoSystemExceptionItemVO {

    private String id;
    private String exceptionNo;
    private String summary;
    private String exceptionType;
    private String sourceModule;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime occurredAt;

    private String severity;
}