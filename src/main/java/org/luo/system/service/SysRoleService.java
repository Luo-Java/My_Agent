package org.luo.system.service;

import com.baomidou.mybatisplus.extension.service.IService;
import org.luo.common.result.PageResult;
import org.luo.system.dto.SaveRoleRequest;
import org.luo.system.dto.SysRoleDTO;
import org.luo.system.dto.UpdateRoleRequest;
import org.luo.system.entity.SysRole;
import org.luo.system.vo.SysRoleVO;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 角色业务：单表 CRUD + 供用户侧复用的角色查询能力。
 * <p>
 * 唯一性与删除前的引用校验都收在实现类，规则不散到 Controller。
 */
public interface SysRoleService extends IService<SysRole> {

    /** 分页查询（关键词模糊匹配编码与名称）。 */
    PageResult<SysRoleVO> page(SysRoleDTO dto);

    /** 全部角色（授权判定与下拉用），按 id 升序。 */
    List<SysRoleVO> listAll();

    /** 新增角色。 */
    SysRoleVO saveRole(SaveRoleRequest req);

    /** 编辑角色（仅展示信息，编码不可改）。 */
    SysRoleVO updateRole(UpdateRoleRequest req);

    /** 删除角色；被用户引用时抛 409。 */
    void deleteRole(Long id);

    /** 按编码取角色，不存在返回 null。 */
    SysRole byCode(String code);

    /** 按 id 批量取角色，key=角色ID。 */
    Map<Long, SysRole> byIds(Collection<Long> ids);

    /** 挂有指定角色的用户ID列表（用户列表按角色筛选用）。 */
    List<Long> userIdsOf(Long roleId);

    /** 校验角色ID集合都存在，缺任一抛 400（保存用户时用）。 */
    void requireAllExists(Collection<Long> roleIds);
}
