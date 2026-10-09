package org.luo.system.constant;

/**
 * 审计动作与对象类型常量（{@code audit_log.action} / {@code audit_log.target_type} 的取值）。
 * <p>
 * 用常量而不是散落的字符串字面量：动作编码是<b>查询维度</b>（按动作筛「谁改过角色」），
 * 写错一个字母不会报错，只会让筛选静默漏掉一批记录 —— 这类错误在审计上尤其致命，因为它表现为
 * 「查不到」，而查不到恰恰是审计最不能被误信的输出。
 */
public final class AuditAction {

    /** 对象类型：用户。 */
    public static final String TARGET_USER = "USER";
    /** 对象类型：角色。 */
    public static final String TARGET_ROLE = "ROLE";

    /** 新增用户（含初始角色分配）。 */
    public static final String CREATE_USER = "CREATE_USER";
    /** 修改用户（资料 / 状态 / 角色，具体变化见 detail）。 */
    public static final String UPDATE_USER = "UPDATE_USER";
    /** 删除用户（连同其角色关联）。 */
    public static final String DELETE_USER = "DELETE_USER";
    /** 更改进户口令（管理员重置 / 用户自改，两者共用本编码，操作者字段可区分）。 */
    public static final String UPDATE_PASSWORD = "UPDATE_PASSWORD";

    /** 新增角色。 */
    public static final String CREATE_ROLE = "CREATE_ROLE";
    /** 修改角色（名称 / 描述，具体变化见 detail；编码不可改，故不在其中）。 */
    public static final String UPDATE_ROLE = "UPDATE_ROLE";
    /** 删除角色。 */
    public static final String DELETE_ROLE = "DELETE_ROLE";

    private AuditAction() {
    }
}
