package org.luo.system.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;
import org.luo.common.BaseBO;

/** 角色分页条件：分页参数继承 BaseBO。 */
@Data
@EqualsAndHashCode(callSuper = false)
public class SysRoleDTO extends BaseBO {

    /** 关键词：同时模糊匹配角色编码与名称。 */
    private String keyword;
}
