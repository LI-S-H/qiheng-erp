package com.qiheng.erp.common.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 哈希工具,主要用于生成稳定幂等键。
 *
 * <p>评分日志表 {@code supplier_score_change_log.change_key} 用本工具生成,
 * 保证同一 (batchNo, supplierId, metricType, supplierProductId) 输入
 * 总产生相同的 SHA-256 输出,实现日志写入幂等。</p>
 *
 * @author Li
 * @since 2026-09-23
 */
public final class HashUtil {

    private static final String SEPARATOR = "|";

    private HashUtil() {
    }

    /**
     * 计算输入字符串的 SHA-256,返回 64 字符十六进制小写串。
     *
     * @param input 输入字符串,允许 null(视为空)
     * @return 64 字符 SHA-256 哈希
     */
    public static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] bytes = md.digest((input == null ? "" : input).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 算法不可用", e);
        }
    }

    /**
     * 拼接多个字段后计算 SHA-256。
     *
     * <p>字段间用 {@code |} 分隔;null 字段用字符串 {@code NULL} 表示(避免拼接歧义)。
     * 适用于"固定键材料生成稳定哈希"的场景。</p>
     *
     * @param parts 待拼接字段
     * @return 64 字符 SHA-256 哈希
     */
    public static String sha256HexConcat(String... parts) {
        if (parts == null || parts.length == 0) {
            return sha256Hex("");
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                sb.append(SEPARATOR);
            }
            sb.append(parts[i] == null ? "NULL" : parts[i]);
        }
        return sha256Hex(sb.toString());
    }
}
