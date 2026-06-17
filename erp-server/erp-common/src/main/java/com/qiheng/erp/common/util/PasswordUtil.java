package com.qiheng.erp.common.util;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 密码加密和校验服务
 *
 * @author Li
 * @since 2026-06-17
 */
@Component
public class PasswordUtil {

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    /**
     * 明文密码加密为 BCrypt 哈希
     *
     * @param rawPassword 明文密码
     * @return BCrypt 哈希值
     */
    public String encode(String rawPassword) {
        return encoder.encode(rawPassword);
    }

    /**
     * 校验明文密码与哈希是否匹配
     *
     * @param rawPassword 明文密码
     * @param hash        数据库中存储的哈希值
     * @return 匹配返回 true
     */
    public boolean matches(String rawPassword, String hash) {
        return encoder.matches(rawPassword, hash);
    }
}
