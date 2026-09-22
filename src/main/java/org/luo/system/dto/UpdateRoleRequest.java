package org.luo.system.dto;

import lombok.Data;

/** 编辑角色请求：{@code code} 不可改（它是授权判定依据），只允许改展示信息。 */
@Data
public class UpdateRoleRequest {

    private Long id;

    private String name;

    private String description;
}
