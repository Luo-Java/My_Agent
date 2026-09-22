package org.luo.system.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;
import org.luo.common.BaseBO;

/** 用户分页条件：分页参数继承 BaseBO，其余字段即筛选条件（为空表示不筛）。 */
@Data
@EqualsAndHashCode(callSuper = false)
public class SysUserDTO extends BaseBO {

    /** 关键词：同时模糊匹配登录名与显示名。 */
    private String keyword;

    /** 状态筛选：1=启用，0=停用，null=全部。 */
    private Integer status;

    /** 角色筛选：只保留挂有该角色的用户，null=全部。 */
    private Long roleId;
}
