package org.luo.system.dto;

import lombok.Data;

/** 登录请求。 */
@Data
public class LoginRequest {

    private String username;

    private String password;
}
