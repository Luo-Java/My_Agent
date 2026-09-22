package org.luo.system.bootstrap;

import lombok.extern.slf4j.Slf4j;
import org.luo.system.constant.SysRoleCode;
import org.luo.system.dto.SaveUserRequest;
import org.luo.system.entity.SysRole;
import org.luo.system.security.JwtProperties;
import org.luo.system.service.SysRoleService;
import org.luo.system.service.SysUserService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 初始管理员引导：仅当 {@code sys_user} 表里一个用户都没有时，创建初始 ADMIN 账号。
 * <p>
 * 存在的必要性：登录校验默认开启，没有任何账号就永远进不去，也没法从界面上创建第一个账号。
 * <p>
 * 只在「表为空」时动手，因此重启不会覆盖或重置任何已有账号；失败只记 ERROR 不阻断启动 ——
 * 最常见的原因是还没执行 {@code sql/system.sql}（表不存在），把原因写进日志比让应用起不来更有用。
 */
@Slf4j
@Component
public class UserBootstrap implements ApplicationRunner {

    private final JwtProperties props;

    private final SysUserService userService;

    private final SysRoleService roleService;

    public UserBootstrap(JwtProperties props, SysUserService userService, SysRoleService roleService) {
        this.props = props;
        this.userService = userService;
        this.roleService = roleService;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!props.isBootstrapAdmin()) {
            log.info("app.jwt.bootstrap-admin=false，跳过初始管理员创建");
            return;
        }
        try {
            bootstrap();
        } catch (Exception e) {
            log.error("初始管理员创建失败，请确认已执行 sql/system.sql 建表与初始化角色：{}", e.getMessage());
        }
    }

    private void bootstrap() {
        long existing = userService.count();
        if (existing > 0) {
            log.info("用户表已有 {} 条记录，跳过初始管理员创建", existing);
            return;
        }
        SysRole adminRole = roleService.byCode(SysRoleCode.ADMIN);
        if (adminRole == null) {
            log.error("未找到内置角色 {}，请先执行 sql/system.sql 初始化角色，本次跳过初始管理员创建", SysRoleCode.ADMIN);
            return;
        }
        SaveUserRequest req = new SaveUserRequest();
        req.setUsername(props.getBootstrapUsername());
        req.setPassword(props.getBootstrapPassword());
        req.setNickname("系统管理员");
        req.setStatus(1);
        req.setRoleIds(List.of(adminRole.getId()));
        userService.saveUser(req);
        // 不把口令打进日志：默认值见 application.yaml 的 app.jwt.bootstrap-password
        log.warn("用户表为空，已创建初始管理员「{}」并授予 {} 角色。"
                        + "口令取自 app.jwt.bootstrap-password（未配置时为内置默认值），请登录后立即修改。",
                props.getBootstrapUsername(), SysRoleCode.ADMIN);
    }
}
