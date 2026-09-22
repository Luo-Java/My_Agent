package org.luo.system.constant;

/**
 * 内置角色编码：与 {@code sql/system.sql} 的初始数据一一对应。
 * <p>
 * 授权判定（{@code @RequireRole}）、初始管理员的创建、默认角色绑定都引用这里，
 * 避免各处散落字符串字面量。
 */
public final class SysRoleCode {

    /** 管理员：可管理用户与角色。 */
    public static final String ADMIN = "ADMIN";

    /** 普通用户：新增用户未指定角色时的默认角色。 */
    public static final String USER = "USER";

    private SysRoleCode() {
    }
}
