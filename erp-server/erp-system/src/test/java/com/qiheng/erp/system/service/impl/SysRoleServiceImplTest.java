package com.qiheng.erp.system.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.qiheng.erp.common.exception.BizException;
import com.qiheng.erp.common.exception.ErrorCode;
import com.qiheng.erp.common.util.RedisUtil;
import com.qiheng.erp.system.mapper.SysRoleMapper;
import com.qiheng.erp.system.mapper.SysUserRoleMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SysRoleServiceImplTest {

    @Mock
    private SysRoleMapper sysRoleMapper;
    @Mock
    private SysUserRoleMapper sysUserRoleMapper;
    @Mock
    private RedisUtil redisUtil;
    @InjectMocks
    private SysRoleServiceImpl service;

    @Test
    void batchDeleteShouldRejectAllWhenAnyRoleIsBoundToUser() {
        when(sysUserRoleMapper.selectCount(any(Wrapper.class))).thenReturn(1L);

        BizException exception = assertThrows(BizException.class,
                () -> service.deleteByIds(List.of("1001", "1002")));

        assertEquals(ErrorCode.ROLE_IN_USE.getCode(), exception.getCode());
        verify(sysRoleMapper, never()).deleteByIds(any(List.class));
        verify(sysUserRoleMapper, never()).delete(any(Wrapper.class));
        verify(redisUtil, never()).delete(any(String.class));
    }

    @Test
    void singleDeleteShouldKeepUserRoleRelationWhenRoleIsUnused() {
        when(sysUserRoleMapper.selectCount(any(Wrapper.class))).thenReturn(0L);

        service.deleteById(1001L);

        verify(sysRoleMapper).deleteById(1001L);
        verify(sysUserRoleMapper, never()).delete(any(Wrapper.class));
        verify(redisUtil).delete("system:options:roles");
    }
}
