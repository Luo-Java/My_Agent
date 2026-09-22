package org.luo.system.dto;

import lombok.Data;

/** 管理员重置他人口令：无需原口令，仅 ADMIN 可调用。 */
@Data
public class ResetPasswordRequest {

    private String password;
}
