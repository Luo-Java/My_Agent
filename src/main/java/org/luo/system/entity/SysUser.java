package org.luo.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 系统用户实体（表 {@code sys_user}）。
 * <p>
 * 只承载持久化字段，对外一律经 {@code SysUserVO} 输出：
 * {@link #password} 标了 {@code @JsonIgnore}，即使误把本实体序列化出去也不会带出口令哈希。
 */
@Data
@NoArgsConstructor
@TableName("sys_user")
public class SysUser {

    /** 用户ID，数据库自增主键。 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 登录名（唯一），创建后不可修改 —— 它是审计与授权的稳定标识。 */
    private String username;

    /** 口令哈希（BCrypt）。只写入由 BCrypt 生成的值，绝不在库里放明文。 */
    @JsonIgnore
    private String password;

    /** 显示名，可为空（前端回落登录名）。 */
    private String nickname;

    /** 邮箱（可选）。 */
    private String email;

    /** 状态：1=启用，0=停用。停用后在拦截器复核阶段被拒，已签发的 token 立即失效。 */
    private Integer status;

    /** 最后登录成功时间。 */
    private LocalDateTime lastLoginAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
