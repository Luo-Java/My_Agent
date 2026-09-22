package org.luo.system.dto;

import lombok.Data;

/** 修改自己的口令：需校验原口令，避免 token 被盗后直接改密踢掉本人。 */
@Data
public class ChangePasswordRequest {

    private String oldPassword;

    private String newPassword;
}
