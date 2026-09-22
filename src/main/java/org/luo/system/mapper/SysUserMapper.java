package org.luo.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.luo.system.entity.SysUser;

/**
 * 用户表 Mapper。
 * <p>
 * 无自定义 SQL：用户分页只需筛 {@code sys_user} 自身字段，角色信息由
 * {@code SysUserServiceImpl} 按 userId 批量补齐（避免 join + GROUP_CONCAT 与分页插件的相互作用）。
 */
public interface SysUserMapper extends BaseMapper<SysUser> {
}
