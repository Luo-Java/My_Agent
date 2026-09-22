package org.luo.system.service.impl;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.luo.common.exception.AiBusinessException;
import org.luo.common.exception.AiErrorCode;
import org.luo.system.dto.ChangePasswordRequest;
import org.luo.system.dto.LoginRequest;
import org.luo.system.entity.SysUser;
import org.luo.system.security.JwtTokenService;
import org.luo.system.security.LoginUser;
import org.luo.system.security.PasswordHasher;
import org.luo.system.service.AuthService;
import org.luo.system.service.SysUserService;
import org.luo.system.vo.LoginResponse;
import org.luo.system.vo.SysUserVO;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * 认证业务实现。
 * <p>
 * 两条刻意的处理：
 * <ul>
 *   <li><b>「登录名不存在」与「口令错误」返回同一提示</b>：区分开等于对外提供一个登录名枚举接口。</li>
 *   <li><b>登录成功后才查角色</b>：{@code loadLoginUser} 会把库中当前角色装进 token 的签发依据，
 *       并把「已停用」再兜一道。</li>
 * </ul>
 */
@Slf4j
@Service
public class AuthServiceImpl implements AuthService {

    @Resource
    private SysUserService userService;

    @Resource
    private JwtTokenService jwtTokenService;

    @Override
    public LoginResponse login(LoginRequest req) {
        String username = req.getUsername() == null ? null : req.getUsername().trim();
        if (username == null || username.isEmpty() || req.getPassword() == null || req.getPassword().isEmpty()) {
            throw new AiBusinessException(AiErrorCode.BAD_REQUEST, "登录名与口令不能为空");
        }
        SysUser user = userService.findByUsername(username);
        if (user == null || !PasswordHasher.matches(req.getPassword(), user.getPassword())) {
            // 失败原因只进日志，不进响应体
            log.warn("登录失败：{}（{}）", username, user == null ? "登录名不存在" : "口令错误");
            throw new AiBusinessException(AiErrorCode.UNAUTHORIZED, "登录名或口令不正确");
        }
        if (user.getStatus() == null || user.getStatus() != 1) {
            log.warn("登录被拒：账号已停用，username={}", username);
            throw new AiBusinessException(AiErrorCode.FORBIDDEN, "账号已停用，请联系管理员");
        }
        LoginUser loginUser = userService.loadLoginUser(user.getId());
        if (loginUser == null) {
            throw new AiBusinessException(AiErrorCode.FORBIDDEN, "账号当前不可用，请联系管理员");
        }
        String token = jwtTokenService.issue(loginUser);
        userService.touchLastLogin(user.getId());
        log.info("登录成功：{}（id={}），角色={}", username, user.getId(), loginUser.roles());
        return new LoginResponse(token, jwtTokenService.expireSeconds(), userService.detail(user.getId()));
    }

    @Override
    public SysUserVO currentUser(Long userId) {
        return userService.detail(userId);
    }

    @Override
    public void changePassword(Long userId, ChangePasswordRequest req) {
        SysUser user = userService.getById(userId);
        if (user == null) {
            throw new AiBusinessException(AiErrorCode.NOT_FOUND, "用户不存在");
        }
        // 校验原口令：token 被他人拿到时，不能靠它直接把本人踢下线
        if (!PasswordHasher.matches(req.getOldPassword(), user.getPassword())) {
            log.warn("改密被拒：原口令不正确，username={}", user.getUsername());
            throw new AiBusinessException(AiErrorCode.BAD_REQUEST, "原口令不正确");
        }
        if (Objects.equals(req.getOldPassword(), req.getNewPassword())) {
            throw new AiBusinessException(AiErrorCode.BAD_REQUEST, "新口令不能与原口令相同");
        }
        userService.updatePassword(userId, req.getNewPassword());
        log.info("用户改密成功：{}（id={}）", user.getUsername(), userId);
    }
}
