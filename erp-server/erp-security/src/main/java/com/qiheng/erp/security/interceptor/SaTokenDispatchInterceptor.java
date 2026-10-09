package com.qiheng.erp.security.interceptor;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.stp.StpUtil;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** Servlet异步完成调度复用初次认证结果，不在没有Sa-Token线程上下文时重复认证。 */
public class SaTokenDispatchInterceptor extends SaInterceptor {
    private static final String AUTHENTICATED_REQUEST = SaTokenDispatchInterceptor.class.getName() + ".authenticated";

    public SaTokenDispatchInterceptor() {
        super(handle -> StpUtil.checkLogin());
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (request.getDispatcherType() == DispatcherType.ASYNC) {
            // 标记只存在于服务器端同一个Servlet request，客户端不能通过参数或header构造。
            if (Boolean.TRUE.equals(request.getAttribute(AUTHENTICATED_REQUEST))) return true;
            throw unauthenticatedDispatch();
        }
        if (request.getDispatcherType() == DispatcherType.ERROR) {
            // 只允许容器错误页调度，防止认证错误在/error递归；普通REQUEST仍按原规则校验。
            if ((request.getContextPath() + "/error").equals(request.getRequestURI())) return true;
            throw unauthenticatedDispatch();
        }
        if (request.getDispatcherType() == DispatcherType.REQUEST) {
            request.removeAttribute(AUTHENTICATED_REQUEST);
        }
        boolean allowed = super.preHandle(request, response, handler);
        if (allowed && request.getDispatcherType() == DispatcherType.REQUEST) {
            request.setAttribute(AUTHENTICATED_REQUEST, Boolean.TRUE);
        }
        return allowed;
    }

    private NotLoginException unauthenticatedDispatch() {
        return NotLoginException.newInstance("login", NotLoginException.NOT_TOKEN, "请求尚未通过登录校验", null);
    }
}
