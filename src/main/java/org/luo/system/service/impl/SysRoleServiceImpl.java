package org.luo.system.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import jakarta.annotation.Resource;
import org.luo.common.exception.AiBusinessException;
import org.luo.common.exception.AiErrorCode;
import org.luo.common.result.PageResult;
import org.luo.system.dto.SaveRoleRequest;
import org.luo.system.dto.SysRoleDTO;
import org.luo.system.dto.UpdateRoleRequest;
import org.luo.system.entity.SysRole;
import org.luo.system.entity.SysUserRole;
import org.luo.system.mapper.SysRoleMapper;
import org.luo.system.mapper.SysUserRoleMapper;
import org.luo.system.service.SysRoleService;
import org.luo.system.vo.SysRoleVO;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 角色业务实现：单表 CRUD 委托 ServiceImpl。
 * <p>
 * 编码是授权判定的依据，因此新增时校验格式、编辑时不允许改；删除前先查用户引用，
 * 被引用直接 409 —— 静默级联删除会凭空收回别人已有的权限。
 */
@Service
public class SysRoleServiceImpl extends ServiceImpl<SysRoleMapper, SysRole> implements SysRoleService {

    /** 角色编码格式：2~32 位大写字母/数字/下划线/短横线。 */
    private static final Pattern CODE_PATTERN = Pattern.compile("[A-Z0-9_-]{2,32}");

    @Resource
    private SysUserRoleMapper userRoleMapper;

    @Override
    public PageResult<SysRoleVO> page(SysRoleDTO dto) {
        LambdaQueryWrapper<SysRole> w = new LambdaQueryWrapper<SysRole>()
                .and(hasText(dto.getKeyword()), q -> q.like(SysRole::getCode, dto.getKeyword())
                        .or().like(SysRole::getName, dto.getKeyword()))
                .orderByAsc(SysRole::getId);
        Page<SysRole> p = dto.toPage();
        IPage<SysRole> ip = super.page(p, w);
        return PageResult.ofMapped(ip, ip.getRecords().stream().map(SysRoleServiceImpl::toVO).toList());
    }

    @Override
    public List<SysRoleVO> listAll() {
        return list(new LambdaQueryWrapper<SysRole>().orderByAsc(SysRole::getId)).stream()
                .map(SysRoleServiceImpl::toVO)
                .toList();
    }

    @Override
    public SysRoleVO saveRole(SaveRoleRequest req) {
        String code = normalizeCode(req.getCode());
        if (count(new LambdaQueryWrapper<SysRole>().eq(SysRole::getCode, code)) > 0) {
            throw new AiBusinessException(AiErrorCode.CONFLICT, "角色编码已存在：" + code);
        }
        SysRole entity = new SysRole();
        entity.setCode(code);
        entity.setName(requireName(req.getName()));
        entity.setDescription(trimToNull(req.getDescription()));
        LocalDateTime now = LocalDateTime.now();
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        baseMapper.insert(entity);
        return toVO(entity);
    }

    @Override
    public SysRoleVO updateRole(UpdateRoleRequest req) {
        SysRole exist = requireRole(req.getId());
        exist.setName(requireName(req.getName()));
        exist.setDescription(trimToNull(req.getDescription()));
        exist.setUpdatedAt(LocalDateTime.now());
        baseMapper.updateById(exist);
        return toVO(exist);
    }

    @Override
    public void deleteRole(Long id) {
        SysRole exist = requireRole(id);
        long used = userRoleMapper.selectCount(
                new LambdaQueryWrapper<SysUserRole>().eq(SysUserRole::getRoleId, id));
        if (used > 0) {
            throw new AiBusinessException(AiErrorCode.CONFLICT,
                    "角色「" + exist.getName() + "」已分配给 " + used + " 个用户，不能删除");
        }
        baseMapper.deleteById(id);
    }

    @Override
    public SysRole byCode(String code) {
        if (!hasText(code)) {
            return null;
        }
        return getOne(new LambdaQueryWrapper<SysRole>().eq(SysRole::getCode, code.trim().toUpperCase()), false);
    }

    @Override
    public Map<Long, SysRole> byIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        List<SysRole> roles = listByIds(ids.stream().distinct().toList());
        Map<Long, SysRole> map = new LinkedHashMap<>();
        roles.forEach(r -> map.put(r.getId(), r));
        return map;
    }

    @Override
    public List<Long> userIdsOf(Long roleId) {
        if (roleId == null) {
            return List.of();
        }
        return userRoleMapper.selectList(new LambdaQueryWrapper<SysUserRole>().eq(SysUserRole::getRoleId, roleId))
                .stream()
                .map(SysUserRole::getUserId)
                .distinct()
                .toList();
    }

    @Override
    public void requireAllExists(Collection<Long> roleIds) {
        if (roleIds == null || roleIds.isEmpty()) {
            return;
        }
        List<Long> distinct = roleIds.stream().distinct().toList();
        if (count(new LambdaQueryWrapper<SysRole>().in(SysRole::getId, distinct)) != distinct.size()) {
            throw new AiBusinessException(AiErrorCode.BAD_REQUEST, "存在无效的角色，请重新选择");
        }
    }

    /** 编码统一转大写后校验格式，非法直接 400。 */
    private static String normalizeCode(String code) {
        String normalized = code == null ? "" : code.trim().toUpperCase();
        if (!CODE_PATTERN.matcher(normalized).matches()) {
            throw new AiBusinessException(AiErrorCode.BAD_REQUEST,
                    "角色编码需为 2~32 位大写字母、数字、下划线或短横线");
        }
        return normalized;
    }

    private static String requireName(String name) {
        String trimmed = trimToNull(name);
        if (trimmed == null) {
            throw new AiBusinessException(AiErrorCode.BAD_REQUEST, "角色名称不能为空");
        }
        if (trimmed.length() > 64) {
            throw new AiBusinessException(AiErrorCode.BAD_REQUEST, "角色名称不能超过 64 个字符");
        }
        return trimmed;
    }

    private SysRole requireRole(Long id) {
        SysRole role = id == null ? null : baseMapper.selectById(id);
        if (role == null) {
            throw new AiBusinessException(AiErrorCode.NOT_FOUND, "角色不存在：id=" + id);
        }
        return role;
    }

    private static SysRoleVO toVO(SysRole role) {
        SysRoleVO vo = new SysRoleVO();
        vo.setId(role.getId());
        vo.setCode(role.getCode());
        vo.setName(role.getName());
        vo.setDescription(role.getDescription());
        vo.setCreatedAt(role.getCreatedAt());
        return vo;
    }

    /** 字符串筛选条件：null 与纯空白都视为不筛。 */
    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String trimmed = s.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
