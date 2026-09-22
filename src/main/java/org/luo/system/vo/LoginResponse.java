package org.luo.system.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 登录成功响应：token 由前端存 localStorage，后续请求按
 * {@code Authorization: Bearer <token>} 携带。
 */
@Data
@AllArgsConstructor
public class LoginResponse {

    /** 签发的 JWT。 */
    private String token;

    /** 有效期（秒），供前端做到期提醒；到期后服务端会直接拒签 token。 */
    private long expiresIn;

    /** 登录用户信息，省掉前端登录后再拉一次 /me。 */
    private SysUserVO user;
}
