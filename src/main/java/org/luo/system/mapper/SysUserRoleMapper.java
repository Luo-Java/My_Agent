package org.luo.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.luo.system.entity.SysUserRole;

/** 用户-角色关联表 Mapper：只按 userId / roleId 批量增删查，无自定义 SQL。 */
public interface SysUserRoleMapper extends BaseMapper<SysUserRole> {
}
