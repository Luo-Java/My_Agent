package org.luo.system.dto;

import lombok.Data;

import java.util.List;

/**
 * 编辑用户请求。
 * <p>
 * 不含 {@code username}（登录名是审计标识，不可改）与 {@code password}（口令走
 * 改密 / 重置两条独立通道），避免「编辑资料」被当成越权改密的入口。
 */
@Data
public class UpdateUserRequest {

    private Long id;

    private String nickname;

    private String email;

    private Integer status;

    /** 角色ID列表：整体覆盖（先删后插），null 表示不动角色。 */
    private List<Long> roleIds;
}
