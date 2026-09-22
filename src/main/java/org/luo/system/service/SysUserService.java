package org.luo.system.service;

import com.baomidou.mybatisplus.extension.service.IService;
import org.luo.common.result.PageResult;
import org.luo.system.dto.SaveUserRequest;
import org.luo.system.dto.SysUserDTO;
import org.luo.system.dto.UpdateUserRequest;
import org.luo.system.entity.SysUser;
import org.luo.system.security.LoginUser;
import org.luo.system.vo.SysUserVO;

/**
 * 用户业务：CRUD + 口令维护 + 登录复核。
 * <p>
 * 「系统必须至少留一个启用的管理员」这条闸门收在实现类：删除、停用、改角色三条路径共用，
 * 避免出现没人能再进管理端的自锁局面。
 */
public interface SysUserService extends IService<SysUser> {

    /** 分页查询（关键词 / 状态 / 角色三个条件是可选叠加）。 */
    PageResult<SysUserVO> page(SysUserDTO dto);

    /** 单条详情。 */
    SysUserVO detail(Long id);

    /** 新增用户（口令必填，落库前转 BCrypt）。 */
    SysUserVO saveUser(SaveUserRequest req);

    /** 编辑用户（不含登录名与口令）。 */
    SysUserVO updateUser(UpdateUserRequest req);

    /** 删除用户（连带清理角色关联）。 */
    void deleteUser(Long id);

    /**
     * 更新口令（编码后落库）。
     * <p>
     * 只负责编码与写入，「是否需要校验原口令」交由调用方决定：
     * 管理员重置（{@code SysUserController}）不需要，本人改密（{@code AuthServiceImpl}）需要。
     */
    void updatePassword(Long userId, String newPassword);
    /** 按登录名取用户（登录流程用），不存在返回 null。 */
    SysUser findByUsername(String username);

    /**
     * 加载登录身份：用户不存在或已停用返回 {@code null}，角色以库中当前值为准。
     * <p>
     * 由 {@code JwtAuthInterceptor} 每次请求调用 —— 这是「改角色 / 停用立即生效」的实现点。
     */
    LoginUser loadLoginUser(Long userId);

    /** 记录登录成功时间（只写 last_login_at，不触碰 updated_at）。 */
    void touchLastLogin(Long userId);
}
