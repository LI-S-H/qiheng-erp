package com.qiheng.erp.system.controller;


import cn.dev33.satoken.stp.StpUtil;
import com.qiheng.erp.common.result.Result;
import com.qiheng.erp.system.domain.dto.SysDeptDto;
import com.qiheng.erp.system.domain.entity.SysDept;
import com.qiheng.erp.system.service.ISysDeptService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * <p>
 * 部门表 前端控制器
 * </p>
 *
 * @author Li
 * @since 2026-06-17
 */
@RestController
@RequestMapping("/system/depts")
@Slf4j
public class SysDeptController {

    @Autowired
    private ISysDeptService sysDeptService;


    /**
     * 查询部门列表，附带用户数量统计（LEFT JOIN + GROUP BY）
     *
     * @param deptName 部门名称筛选
     * @param status   状态筛选
     * @return 部门列表（含 userCount）
     */
    @GetMapping
    public Result<List<SysDeptDto>> list(
            @RequestParam(required = false) String deptName,
            @RequestParam(required = false) Integer status)
    {
        log.info("查询部门列表，deptName: {}, status: {}", deptName, status);
        List<SysDeptDto> list = sysDeptService.listWithUserCount(deptName, status);
        return Result.ok(list);
    }
    
    /**
     * 保存部门
     * @param dept 部门信息
     * @return 保存后的部门信息（含 deptId）
     */
    @PostMapping
    public Result<SysDeptDto> save(@RequestBody SysDept dept) {
        log.info("保存部门: {}", dept);
        //鉴权
        StpUtil.checkPermission("system:dept:manage");
        SysDeptDto dto = sysDeptService.saveDept(dept);
        return Result.ok(dto);
    }


       
       
}