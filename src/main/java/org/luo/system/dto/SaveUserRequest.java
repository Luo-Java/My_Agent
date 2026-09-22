package org.luo.system.dto;

import lombok.Data;

import java.util.List;

/**
 * 新增用户请求。
 * <p>
 * 不复用 {@code SysUser} 实体：口令只能从请求体进、经 BCrypt 后落库，实体字段不直接接收外部输入。
 */
@Data
public class SaveUserRequest {

    private String username;

    /** 明文口令，仅在本请求内存在，落库前转 BCrypt 哈希。 */
    private String password;

    private String nickname;

    private String email;

    /** 状态，缺省按启用处理。 */
    private Integer status;

    /** 角色ID列表，缺省绑定 USER 角色。 */
    private List<Long> roleIds;
}
