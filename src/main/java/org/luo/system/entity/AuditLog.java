package org.luo.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 管理操作审计记录（对应 {@code audit_log} 表）：回答「谁、在什么时候、把谁的什么改成了什么」。
 * <p>
 * <b>为什么需要它</b>：权限变更此前只在业务表上留下<b>结果</b> —— {@code sys_user_role} 换了一行、
 * {@code sys_user.status} 从 1 变成 0。结果本身不说明是谁干的、为什么干的。等出事再回头查「这个管理员
 * 是谁授权的」，业务表给不出答案。审计表要回答的正是这个「过程」，所以它<b>只增不改</b>
 * （没有任何更新/删除的入口，这是审计记录可信的前提）。
 * <p>
 * <b>用户名存快照</b>：{@code sys_user} 里那行可能被改名甚至删除，只留 {@code user_id} 会让记录变成一串
 * 读不懂的数字。审计记录必须自解释 —— 这正是它与普通业务外键的区别。
 * <p>
 * <b>{@code detail} 落库前经 PII 脱敏</b>（见 {@code PiiMasker}）：审计表把「被改的东西」抄了一份留档，
 * 如果原样抄，它自己就变成了新的敏感信息聚集地 —— 那等于一边做安全一边造窟窿。
 */
@Data
@NoArgsConstructor
@TableName("audit_log")
public class AuditLog {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 操作者用户 ID（关联 {@code sys_user.id}）；为空 = 系统自身动作或拿不到身份上下文。 */
    private Long userId;

    /** 操作者用户名<b>快照</b>（与 {@code sys_user.username} 无关，用户改名后此值不跟着变）。 */
    private String username;

    /** 动作编码（取值见 {@code AuditAction}）。 */
    private String action;

    /** 对象类型（USER / ROLE 等）。 */
    private String targetType;

    /** 对象 ID（字符串：便于兼容非数字主键）。 */
    private String targetId;

    /** 明细（已 PII 脱敏，超长在写入前截断）。 */
    private String detail;

    /** 请求来源 IP（拿不到则为 null —— 不编造）。 */
    private String ip;

    /** 发生时间。 */
    private LocalDateTime createdAt;
}
