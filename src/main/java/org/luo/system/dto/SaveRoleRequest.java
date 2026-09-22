package org.luo.system.dto;

import lombok.Data;

/** 新增角色请求。 */
@Data
public class SaveRoleRequest {

    private String code;

    private String name;

    private String description;
}
