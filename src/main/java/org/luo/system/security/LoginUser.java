package org.luo.system.security;

import java.util.Set;

/**
 * 当前登录用户（一次请求身份的唯一载体），由 {@link JwtAuthInterceptor} 在放行前构造并存入
 * {@link AuthContext}，业务侧只读不改。
 *
 * @param id       用户ID
 * @param username 登录名
 * @param nickname 显示名（为空时回落登录名）
 * @param roles    角色编码集合，权限判定取并集
 */
public record LoginUser(Long id, String username, String nickname, Set<String> roles) {

    /** 是否具备指定角色。 */
    public boolean hasRole(String roleCode) {
        return roleCode != null && roles != null && roles.contains(roleCode);
    }

    /** 展示名：昵称为空时回落登录名，避免前端各写一份兜底。 */
    public String displayName() {
        return nickname == null || nickname.isBlank() ? username : nickname;
    }
}
