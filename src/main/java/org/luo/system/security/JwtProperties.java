package org.luo.system.security;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * JWT 鉴权配置（{@code app.jwt.*}）。
 * <p>
 * {@link #enabled} 打开后，{@code /api/**} 除登录接口（见 {@link JwtAuthInterceptor} 的匿名路径）
 * 外，一律要求 {@code Authorization: Bearer <token>}；关闭时行为与引入用户系统前完全一致（不拦截）。
 */
@Data
@ConfigurationProperties(prefix = "app.jwt")
public class JwtProperties {

    /** 是否启用强制登录（默认 true）。关闭后 /api/** 不再校验 token。 */
    private boolean enabled = true;

    /**
     * HMAC-SHA256 签名密钥。
     * <p>
     * 不进仓库：由环境变量 {@code JWT_SECRET} 注入。留空时启动生成随机密钥并打 WARN ——
     * 仅够本地开发，重启后此前签发的 token（含浏览器里存的）全部失效，需要重新登录。
     */
    private String secret = "";

    /** token 有效期（分钟），默认 12 小时。 */
    private int expireMinutes = 720;

    /** 签发者标识，写入 JWT 的 iss 声明。 */
    private String issuer = "my-agent";

    /** 是否在「表内无任何用户」时自动创建初始管理员（默认 true，否则首次无法登录）。 */
    private boolean bootstrapAdmin = true;

    /** 初始管理员登录名。 */
    private String bootstrapUsername = "admin";

    /** 初始管理员口令，默认仅够跑通流程，生产必须用 JWT_BOOTSTRAP_PASSWORD 覆盖。 */
    private String bootstrapPassword = "admin123";
}
