package org.luo.system.security;

import cn.hutool.crypto.digest.BCrypt;
import org.luo.common.exception.AiBusinessException;
import org.luo.common.exception.AiErrorCode;

/**
 * 口令编码与校验：全项目唯一处理口令哈希的地方（复用 Hutool 的 BCrypt 实现，不引额外依赖）。
 * <p>
 * BCrypt 自带随机盐，同一明文每次哈希结果都不同，库里只存哈希；校验走 {@link #matches}，
 * 不要自己写字符串相等判断。
 */
public final class PasswordHasher {

    /** 口令长度下限。 */
    private static final int MIN_LENGTH = 6;

    /**
     * 口令长度上限。
     * <p>
     * BCrypt 只取前 72 字节参与运算，超长部分会被静默忽略 —— 与其留下「输入了 100 位却只认前 72 字节」
     * 的错觉，不如直接拒掉。
     */
    private static final int MAX_LENGTH = 64;

    private PasswordHasher() {
    }

    /** 生成口令哈希（自带盐）。 */
    public static String hash(String rawPassword) {
        requireValid(rawPassword);
        return BCrypt.hashpw(rawPassword);
    }

    /** 校验明文与哈希是否匹配；任一侧为空一律不通过。 */
    public static boolean matches(String rawPassword, String hashed) {
        if (rawPassword == null || rawPassword.isEmpty() || hashed == null || hashed.isBlank()) {
            return false;
        }
        try {
            return BCrypt.checkpw(rawPassword, hashed);
        } catch (IllegalArgumentException e) {
            // 库里存了非 BCrypt 格式的值（如历史明文）：按不匹配处理，避免异常冒泡成 500
            return false;
        }
    }

    /** 口令强度校验，不满足直接抛 400。 */
    public static void requireValid(String rawPassword) {
        if (rawPassword == null || rawPassword.isBlank()) {
            throw new AiBusinessException(AiErrorCode.BAD_REQUEST, "口令不能为空");
        }
        if (rawPassword.length() < MIN_LENGTH || rawPassword.length() > MAX_LENGTH) {
            throw new AiBusinessException(AiErrorCode.BAD_REQUEST,
                    "口令长度需为 " + MIN_LENGTH + "~" + MAX_LENGTH + " 位");
        }
    }
}
