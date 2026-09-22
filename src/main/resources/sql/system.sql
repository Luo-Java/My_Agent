-- ============================================================================
-- 用户管理系统建表脚本：sys_user（用户）/ sys_role（角色）/ sys_user_role（关联）
-- 执行：mysql -uroot -p agent < src/main/resources/sql/system.sql
--
-- 幂等：可重复执行（CREATE TABLE IF NOT EXISTS + INSERT IGNORE），不会覆盖已有数据。
-- 初始管理员（admin）不在这里插入 —— 口令哈希需 BCrypt 生成，由应用启动引导
-- UserBootstrap 在「表内无任何用户」时创建，见 application.yaml 的 app.jwt.bootstrap-*。
-- ============================================================================

-- 系统用户表：登录账号，口令只存 BCrypt 哈希（自带盐，不可逆），全程不出库
CREATE TABLE IF NOT EXISTS sys_user (
    id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '用户ID',
    username      VARCHAR(64)  NOT NULL                COMMENT '登录名（唯一，创建后不可修改）',
    password      VARCHAR(100) NOT NULL                COMMENT '口令哈希（BCrypt，固定 60 字符，留余量）',
    nickname      VARCHAR(64)  DEFAULT NULL            COMMENT '显示名，为空时前端回落到登录名',
    email         VARCHAR(128) DEFAULT NULL            COMMENT '邮箱（可选）',
    status        TINYINT(1)   NOT NULL DEFAULT 1      COMMENT '状态：1=启用，0=停用（停用后拒绝登录且已签发 token 立即失效）',
    last_login_at DATETIME     DEFAULT NULL            COMMENT '最后登录成功时间',
    created_at    DATETIME     DEFAULT NULL            COMMENT '创建时间',
    updated_at    DATETIME     DEFAULT NULL            COMMENT '最后更新时间（含口令重置）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sys_user_username (username)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '系统用户表：登录账号与口令哈希';

-- 系统角色表：授权判定用 code（ADMIN/USER），name/description 仅展示
CREATE TABLE IF NOT EXISTS sys_role (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '角色ID',
    code        VARCHAR(64)  NOT NULL                COMMENT '角色编码（唯一，授权判定依据，如 ADMIN / USER；创建后不可修改）',
    name        VARCHAR(64)  NOT NULL                COMMENT '角色名称（展示用，如 管理员 / 普通用户）',
    description VARCHAR(255) DEFAULT NULL            COMMENT '角色说明',
    created_at  DATETIME     DEFAULT NULL            COMMENT '创建时间',
    updated_at  DATETIME     DEFAULT NULL            COMMENT '最后更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sys_role_code (code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '系统角色表：用户权限的最小划分单位';

-- 用户-角色关联表：一个用户可挂多个角色，权限取并集
CREATE TABLE IF NOT EXISTS sys_user_role (
    id         BIGINT   NOT NULL AUTO_INCREMENT COMMENT '关联ID',
    user_id    BIGINT   NOT NULL                COMMENT '用户ID（关联 sys_user.id）',
    role_id    BIGINT   NOT NULL                COMMENT '角色ID（关联 sys_role.id）',
    created_at DATETIME DEFAULT NULL            COMMENT '授权时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sys_user_role (user_id, role_id),
    -- 反向索引：删除角色前要按 role_id 统计引用用户数
    KEY idx_sys_user_role_role (role_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '用户-角色关联表：多对多授权';

-- 初始角色（幂等）：ADMIN 可管理用户与角色；USER 为登录后的默认角色
INSERT IGNORE INTO sys_role (code, name, description, created_at, updated_at)
VALUES ('ADMIN', '管理员', '可管理用户与角色，拥有全部接口权限', NOW(), NOW()),
       ('USER', '普通用户', '可使用对话、教务等功能，不能管理用户与角色', NOW(), NOW());
