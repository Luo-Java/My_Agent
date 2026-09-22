package org.luo.system.vo;

import lombok.Data;

import java.time.LocalDateTime;

/** 角色对外视图。 */
@Data
public class SysRoleVO {

    private Long id;

    /** 角色编码（授权判定依据）。 */
    private String code;

    private String name;

    private String description;

    private LocalDateTime createdAt;
}
