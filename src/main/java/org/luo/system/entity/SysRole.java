package org.luo.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 系统角色实体（表 {@code sys_role}）。
 * <p>
 * {@link #code} 是授权判定依据（{@code @RequireRole("ADMIN")} 比的就是它），创建后不可修改；
 * {@link #name} / {@link #description} 只用于展示，可随时改。
 */
@Data
@NoArgsConstructor
@TableName("sys_role")
public class SysRole {

    /** 角色ID，数据库自增主键。 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 角色编码（唯一），如 ADMIN / USER。授权判定依据，不可修改。 */
    private String code;

    /** 角色名称（展示用）。 */
    private String name;

    /** 角色说明。 */
    private String description;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
