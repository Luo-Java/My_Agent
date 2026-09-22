package org.luo.system.controller;

import jakarta.annotation.Resource;
import org.luo.common.result.RestResult;
import org.luo.system.dto.ChangePasswordRequest;
import org.luo.system.dto.LoginRequest;
import org.luo.system.security.AuthContext;
import org.luo.system.service.AuthService;
import org.luo.system.vo.LoginResponse;
import org.luo.system.vo.SysUserVO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证接口（/api/auth）。
 * <ul>
 *   <li>{@code POST /login} —— 登录，返回 token 与用户信息；<b>全项目唯一的免登录接口</b></li>
 *   <li>{@code GET /me} —— 当前登录用户（前端刷新页面后校验 token 是否仍有效）</li>
 *   <li>{@code POST /password} —— 修改自己的口令</li>
 * </ul>
 * 没有 logout 接口：JWT 是无状态的，服务端没有会话可销毁，退出登录 = 前端丢弃本地 token。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    @Resource
    private AuthService authService;

    @PostMapping("/login")
    public RestResult<LoginResponse> login(@RequestBody LoginRequest req) {
        return RestResult.ok(authService.login(req));
    }

    @GetMapping("/me")
    public RestResult<SysUserVO> me() {
        return RestResult.ok(authService.currentUser(AuthContext.require().id()));
    }

    @PostMapping("/password")
    public RestResult<Void> changePassword(@RequestBody ChangePasswordRequest req) {
        authService.changePassword(AuthContext.require().id(), req);
        return RestResult.ok();
    }
}
