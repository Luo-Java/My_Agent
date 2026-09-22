package org.luo.system.service;

import org.luo.system.dto.ChangePasswordRequest;
import org.luo.system.dto.LoginRequest;
import org.luo.system.vo.LoginResponse;
import org.luo.system.vo.SysUserVO;

/** 认证业务：登录签发 token、取当前用户、改自己的口令。 */
public interface AuthService {

    /** 登录：校验口令与账号状态，成功签发 JWT 并记录登录时间。 */
    LoginResponse login(LoginRequest req);

    /** 取当前登录用户信息（/me）。 */
    SysUserVO currentUser(Long userId);

    /** 修改自己的口令（需校验原口令）。 */
    void changePassword(Long userId, ChangePasswordRequest req);
}
