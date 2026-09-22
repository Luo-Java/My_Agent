package org.luo.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 用户-角色关联实体（表 {@code sys_user_role}）：多对多授权，一个用户可挂多个角色。
 * <p>
 * 无独立业务含义，只由 {@code SysUserService} 在保存用户时整体重写（先删后插），
 * 不单独对外暴露增删接口。
 */
@Data
@NoArgsConstructor
@TableName("sys_user_role")
public class SysUserRole {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long roleId;

    private LocalDateTime createdAt;
}
