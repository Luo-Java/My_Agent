package org.luo.system.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 用户对外视图：口令哈希永不出现在这里（这是对外输出用户的唯一类型）。
 * <p>
 * 角色三件套各司其职：{@link #roleIds} 供编辑表单回显，{@link #roleCodes} 供前端做按钮级判断，
 * {@link #roleNames} 是后端拼好的展示串 —— 前端不再做 id→名称映射。
 */
@Data
public class SysUserVO {

    private Long id;

    private String username;

    private String nickname;

    private String email;

    /** 1=启用，0=停用。 */
    private Integer status;

    private List<Long> roleIds;

    private List<String> roleCodes;

    /** 角色名称拼接串（多个以「、」分隔），直接给表格展示。 */
    private String roleNames;

    private LocalDateTime lastLoginAt;

    private LocalDateTime createdAt;
}
