package com.qiheng.erp.security.config;

import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.stp.StpUtil;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Sa-Token 路由拦截配置
 */
@Configuration
public class SaTokenConfig implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new SaInterceptor(handle -> StpUtil.checkLogin()))
                .addPathPatterns("/**")
                .excludePathPatterns(
                        // 登录接口
                        "/auth/login",
                        // Knife4j 文档
                        "/doc.html",
                        "/swagger-resources",
                        "/swagger-resources/**",
                        "/v3/api-docs",
                        "/v3/api-docs/**",
                        "/webjars/**",
                        "/favicon.ico"
                );
    }
}
