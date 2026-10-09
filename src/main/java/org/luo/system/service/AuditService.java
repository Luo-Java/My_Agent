package org.luo.system.service;

import org.luo.common.result.PageResult;
import org.luo.system.entity.AuditLog;

/**
 * 管理操作审计：把 ADMIN 的敏感动作（给谁加了什么角色、停用了谁、删了什么）落到 {@code audit_log}，
 * 并提供只读查询。
 * <p>
 * <b>为什么需要它</b>：权限变更此前只在业务表上留下<b>结果</b> —— {@code sys_user_role} 换了一行、
 * {@code sys_user.status} 从 1 变成 0。结果本身不说明是谁干的、为什么干的。等出事再回头查「这个管理员
 * 是谁授权的」，业务表给不出答案。审计要回答的正是这个<b>过程</b>。
 * <p>
 * <b>只增不改</b>：本接口<b>没有</b>任何更新或删除方法 —— 审计记录可信的前提就是它改不掉。要清理只能由
 * DBA 直接操作数据库，那是另一条有权重、有痕迹的路径，不该由应用随手提供。
 * <p>
 * <b>为什么放在 system 而不是 ai</b>：本模块的依赖方向是单向的 {@code ai → system}（ai 侧有 12 处引用
 * system 的安全上下文与用户服务，system 侧此前零处引用 ai）。审计记录的是权限相关操作、被记录的也主要是
 * 本模块的写路径，放在这里方向自然；放到 ai 会把依赖变成双向。
 * <p>
 * 实现见 {@code AuditServiceImpl}（模块内统一「接口 + 实现」，不因它是旁路组件而破例）。
 */
public interface AuditService {

    /**
     * 记录一条审计。操作者取自当前登录态；拿不到则记空 —— <b>不编造</b>假身份（审计里出现一个不存在的
     * 操作者，比缺一个字段更误导）。
     * <p>
     * <b>失败不拖垮业务</b>：审计是旁路，管理操作才是用户要完成的事。实现里对落库异常一律 catch 后记 ERROR
     * （不静默：日志能看到哪个动作没留痕），业务继续。
     *
     * @param action     动作编码（取值见 {@code AuditAction}）
     * @param targetType 对象类型（{@code AuditAction.TARGET_*}）
     * @param targetId   对象 ID（任意类型，内部转字符串；null 允许）
     * @param detail     明细（可读的变化描述；写入前会做 PII 脱敏与截断）
     */
    void record(String action, String targetType, Object targetId, String detail);

    /**
     * 分页查询（仅 ADMIN，角色校验在 Controller 层）。时间倒序。
     *
     * @param page   页码（从 1 起，&lt;1 按 1）
     * @param size   每页条数（&lt;1 按 20，&gt;100 截断到 100）
     * @param action 动作编码筛选（空 = 全部）
     */
    PageResult<AuditLog> page(int page, int size, String action);
}
