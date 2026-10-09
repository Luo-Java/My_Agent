package org.luo.system.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.luo.common.exception.AiBusinessException;
import org.luo.common.exception.AiErrorCode;
import org.luo.common.result.PageResult;
import org.luo.system.constant.AuditAction;
import org.luo.system.constant.SysRoleCode;
import org.luo.system.dto.SaveUserRequest;
import org.luo.system.dto.SysUserDTO;
import org.luo.system.dto.UpdateUserRequest;
import org.luo.system.entity.SysRole;
import org.luo.system.entity.SysUser;
import org.luo.system.entity.SysUserRole;
import org.luo.system.mapper.SysUserMapper;
import org.luo.system.mapper.SysUserRoleMapper;
import org.luo.system.security.AuthContext;
import org.luo.system.security.LoginUser;
import org.luo.system.security.PasswordHasher;
import org.luo.system.service.AuditService;
import org.luo.system.service.SysRoleService;
import org.luo.system.service.SysUserService;
import org.luo.system.vo.SysUserVO;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 用户业务实现：单表 CRUD 委托 ServiceImpl，角色关联单独维护。
 * <p>
 * 几个刻意的取舍：
 * <ul>
 *   <li><b>角色信息不在分页 SQL 里 join</b>：先按条件分页查 {@code sys_user}，再用一次
 *       {@code IN} 查询补齐角色。避免 {@code GROUP BY} 与分页插件生成 count 语句时相互影响。</li>
 *   <li><b>角色的整体覆盖而非增量</b>：保存时先删后插，前端传的就是最终态，不用去猜增量。</li>
 *   <li><b>「至少留一个启用的管理员」收在本类</b>：删除、停用、摘角色三条路径共用同一道闸门，
 *       避免出现没人能再进管理端的自锁。</li>
 * </ul>
 */
@Slf4j
@Service
public class SysUserServiceImpl extends ServiceImpl<SysUserMapper, SysUser> implements SysUserService {

    /** 登录名格式：2~64 位字母/数字/下划线/点/短横线/@。 */
    private static final Pattern USERNAME_PATTERN = Pattern.compile("[A-Za-z0-9_.@-]{2,64}");

    @Resource
    private SysRoleService roleService;

    @Resource
    private SysUserRoleMapper userRoleMapper;

    /** 管理操作审计：本类的四个写方法（增 / 改 / 删 / 改口令）都是权限相关动作，逐条留痕。 */
    @Resource
    private AuditService auditService;

    // ==================== 查询 ====================

    @Override
    public PageResult<SysUserVO> page(SysUserDTO dto) {
        LambdaQueryWrapper<SysUser> w = new LambdaQueryWrapper<SysUser>()
                .and(hasText(dto.getKeyword()), q -> q.like(SysUser::getUsername, dto.getKeyword())
                        .or().like(SysUser::getNickname, dto.getKeyword()))
                .eq(dto.getStatus() != null, SysUser::getStatus, dto.getStatus())
                .orderByAsc(SysUser::getId);
        Page<SysUser> p = dto.toPage();
        if (dto.getRoleId() != null) {
            List<Long> userIds = roleService.userIdsOf(dto.getRoleId());
            if (userIds.isEmpty()) {
                // 该角色下没有用户：直接回空页，不去构造 IN ()
                return PageResult.ofMapped(p, List.of());
            }
            w.in(SysUser::getId, userIds);
        }
        IPage<SysUser> ip = super.page(p, w);
        List<SysUser> users = ip.getRecords();
        Map<Long, List<Long>> roleIdsByUser = roleIdsByUser(users.stream().map(SysUser::getId).toList());
        Map<Long, SysRole> roleMap = roleService.byIds(
                roleIdsByUser.values().stream().flatMap(List::stream).distinct().toList());
        List<SysUserVO> vos = users.stream()
                .map(u -> assemble(u, roleIdsByUser.getOrDefault(u.getId(), List.of()), roleMap))
                .toList();
        return PageResult.ofMapped(ip, vos);
    }

    @Override
    public SysUserVO detail(Long id) {
        SysUser user = requireUser(id);
        List<Long> roleIds = roleIdsOfUser(id);
        return assemble(user, roleIds, roleService.byIds(roleIds));
    }

    @Override
    public SysUser findByUsername(String username) {
        if (!hasText(username)) {
            return null;
        }
        return getOne(new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, username.trim()), false);
    }

    @Override
    public LoginUser loadLoginUser(Long userId) {
        SysUser user = userId == null ? null : baseMapper.selectById(userId);
        if (user == null) {
            log.debug("放行失败：用户不存在，id={}", userId);
            return null;
        }
        if (user.getStatus() == null || user.getStatus() != 1) {
            log.debug("放行失败：用户已停用，id={}", userId);
            return null;
        }
        return new LoginUser(user.getId(), user.getUsername(), user.getNickname(), roleCodesOf(userId));
    }

    // ==================== 写入 ====================

    @Override
    public SysUserVO saveUser(SaveUserRequest req) {
        String username = requireUsername(req.getUsername());
        requireUniqueUsername(username, null);
        // 口令先编码再落库：实体上不会出现明文
        String hashed = PasswordHasher.hash(req.getPassword());
        List<Long> roleIds = req.getRoleIds() == null || req.getRoleIds().isEmpty()
                ? defaultRoleIds()
                : distinctRoleIds(req.getRoleIds());

        SysUser user = new SysUser();
        user.setUsername(username);
        user.setPassword(hashed);
        user.setNickname(trimToNull(req.getNickname()));
        user.setEmail(trimToNull(req.getEmail()));
        user.setStatus(req.getStatus() == null ? 1 : requireStatus(req.getStatus()));
        LocalDateTime now = LocalDateTime.now();
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        baseMapper.insert(user);

        replaceRoles(user.getId(), roleIds);
        log.info("新增用户：{}（id={}），角色={}", username, user.getId(), roleIds);
        auditService.record(AuditAction.CREATE_USER, AuditAction.TARGET_USER, user.getId(),
                String.format("新增用户 %s；状态 %s；角色 %s",
                        username, statusText(user.getStatus()), roleIdsText(roleIds)));
        return detail(user.getId());
    }

    @Override
    public SysUserVO updateUser(UpdateUserRequest req) {
        SysUser exist = requireUser(req.getId());
        // 旧值必须先取：下面会把新值写进实体，之后就拿不到「改前是什么」了 —— 而审计的全部价值就在这个对比
        int oldStatus = defaultStatus(exist.getStatus());
        List<Long> oldRoleIds = roleIdsOfUser(exist.getId());
        int newStatus = req.getStatus() == null ? oldStatus : requireStatus(req.getStatus());
        List<Long> newRoleIds = req.getRoleIds() == null ? oldRoleIds : distinctRoleIds(req.getRoleIds());

        LoginUser current = AuthContext.get();
        if (current != null && current.id().equals(exist.getId()) && newStatus != 1) {
            throw new AiBusinessException(AiErrorCode.CONFLICT, "不能停用当前登录的账号");
        }
        // ADMIN 角色不存在时（被删）不能做 contains 判定：newRoleIds 的兜底值是 List.of()，
        // 而 JDK 的 List.of().contains(null) 直接抛 NPE（ArrayList/Stream.toList 返回 false 不抛）。
        // 此时语义上「没有 ADMIN 可摘」⇒ 无需校验，直接放行，与 assertAdminRemains 的 null 早退一致。
        Long adminId = adminRoleId();
        if (adminId == null) {
            assertAdminRemains(exist.getId());
        } else if (newStatus != 1 || !newRoleIds.contains(adminId)) {
            assertAdminRemains(exist.getId());
        }

        exist.setNickname(trimToNull(req.getNickname()));
        exist.setEmail(trimToNull(req.getEmail()));
        exist.setStatus(newStatus);
        exist.setUpdatedAt(LocalDateTime.now());
        baseMapper.updateById(exist);

        if (req.getRoleIds() != null) {
            replaceRoles(exist.getId(), newRoleIds);
        }
        log.info("更新用户：{}（id={}），状态={}，角色={}", exist.getUsername(), exist.getId(), newStatus, newRoleIds);
        auditService.record(AuditAction.UPDATE_USER, AuditAction.TARGET_USER, exist.getId(),
                updateDetail(oldStatus, newStatus, oldRoleIds, newRoleIds, exist));
        return detail(exist.getId());
    }

    @Override
    public void deleteUser(Long id) {
        SysUser exist = requireUser(id);
        LoginUser current = AuthContext.get();
        if (current != null && current.id().equals(id)) {
            throw new AiBusinessException(AiErrorCode.CONFLICT, "不能删除当前登录的账号");
        }
        if (isAdmin(id)) {
            assertAdminRemains(id);
        }
        // 关联表无外键约束，先清关联再删用户，避免留下悬挂授权
        userRoleMapper.delete(new LambdaQueryWrapper<SysUserRole>().eq(SysUserRole::getUserId, id));
        baseMapper.deleteById(id);
        log.info("删除用户：{}（id={}）", exist.getUsername(), id);
        auditService.record(AuditAction.DELETE_USER, AuditAction.TARGET_USER, id,
                "删除用户 " + exist.getUsername() + "（连同其角色关联）");
    }

    @Override
    public void updatePassword(Long userId, String newPassword) {
        SysUser exist = requireUser(userId);
        String hashed = PasswordHasher.hash(newPassword);
        baseMapper.update(null, new LambdaUpdateWrapper<SysUser>()
                .eq(SysUser::getId, userId)
                .set(SysUser::getPassword, hashed)
                .set(SysUser::getUpdatedAt, LocalDateTime.now()));
        log.info("更新口令：{}（id={}）", exist.getUsername(), userId);
        // 口令本身绝不进明细：审计表要能公开查（ADMIN 面板），口令一旦落进去就是新的泄露面
        auditService.record(AuditAction.UPDATE_PASSWORD, AuditAction.TARGET_USER, userId,
                "更新口令：" + exist.getUsername());
    }

    @Override
    public void touchLastLogin(Long userId) {
        // 只写 last_login_at：登录不算资料变更，不该刷新 updated_at
        baseMapper.update(null, new LambdaUpdateWrapper<SysUser>()
                .eq(SysUser::getId, userId)
                .set(SysUser::getLastLoginAt, LocalDateTime.now()));
    }

    // ==================== 内部辅助 ====================

    /** 状态码的可读文案（审计明细用，与界面口径一致）。 */
    private static String statusText(int status) {
        return status == 1 ? "启用" : "停用";
    }

    /**
     * 角色 ID 列表的可读文案。<b>输出前排序</b>：两个列表只要元素集合相同就该被判定为「没变」，
     * 而 SQL 返回的顺序不保证稳定 —— 不排序会让「只是顺序变了」被记成一次角色变更。
     */
    private static String roleIdsText(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return "无";
        }
        List<Long> sorted = new ArrayList<>(ids);
        sorted.sort(Comparator.naturalOrder());
        return sorted.toString();
    }

    /**
     * 拼「修改用户」的审计明细：只写<b>真的变了</b>的项。
     * 全量罗列会让明细里塞满无变化的噪声 —— 审计要的是一眼看出「这次改动到底动了什么」。
     */
    private static String updateDetail(int oldStatus, int newStatus, List<Long> oldRoleIds,
                                       List<Long> newRoleIds, SysUser after) {
        StringBuilder d = new StringBuilder();
        if (oldStatus != newStatus) {
            d.append("状态 ").append(statusText(oldStatus)).append("→").append(statusText(newStatus)).append("；");
        }
        String before = roleIdsText(oldRoleIds);
        String now = roleIdsText(newRoleIds);
        if (!before.equals(now)) {
            d.append("角色 ").append(before).append("→").append(now).append("；");
        }
        d.append("昵称=").append(describe(after.getNickname())).append("，邮箱=").append(describe(after.getEmail()));
        return d.toString();
    }

    /** 空值展示成「（空）」而不是 null：审计明细里的 null 分不清「没填」与「没读到」。 */
    private static String describe(String s) {
        return s == null || s.isBlank() ? "（空）" : s;
    }

    /**
     * 保证系统至少剩一个「启用状态的 ADMIN」（排除 {@code excludedUserId} 后仍有）。
     * <p>
     * 删除用户、停用用户、摘掉某人的 ADMIN 角色三条路径共用 —— 少了这道闸门，
     * 最后一个管理员可以把自己锁在管理端外面，且没有任何界面能救回来。
     */
    private void assertAdminRemains(Long excludedUserId) {
        Long adminRoleId = adminRoleId();
        if (adminRoleId == null) {
            return;
        }
        List<Long> adminIds = roleService.userIdsOf(adminRoleId);
        if (adminIds.isEmpty()) {
            return;
        }
        long remaining = count(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getStatus, 1)
                .in(SysUser::getId, adminIds)
                .ne(excludedUserId != null, SysUser::getId, excludedUserId));
        if (remaining == 0) {
            throw new AiBusinessException(AiErrorCode.CONFLICT,
                    "系统必须保留至少一个启用状态的管理员账号");
        }
    }

    /** 用户的角色ID列表（一次查询）。 */
    private List<Long> roleIdsOfUser(Long userId) {
        return roleIdsByUser(List.of(userId)).getOrDefault(userId, List.of());
    }

    /** 批量取「用户 → 角色ID列表」，供分页后补角色，避免逐行查询。 */
    private Map<Long, List<Long>> roleIdsByUser(List<Long> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        return userRoleMapper.selectList(new LambdaQueryWrapper<SysUserRole>().in(SysUserRole::getUserId, userIds))
                .stream()
                .collect(Collectors.groupingBy(SysUserRole::getUserId,
                        Collectors.mapping(SysUserRole::getRoleId, Collectors.toList())));
    }

    /** 用户当前的角色编码集合。 */
    private Set<String> roleCodesOf(Long userId) {
        List<Long> roleIds = roleIdsOfUser(userId);
        if (roleIds.isEmpty()) {
            return Set.of();
        }
        return roleService.byIds(roleIds).values().stream()
                .map(SysRole::getCode)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private boolean isAdmin(Long userId) {
        return roleCodesOf(userId).contains(SysRoleCode.ADMIN);
    }

    private Long adminRoleId() {
        SysRole admin = roleService.byCode(SysRoleCode.ADMIN);
        return admin == null ? null : admin.getId();
    }

    /** 未指定角色时的默认角色（USER）；该角色被删掉的极端情况下返回空列表。 */
    private List<Long> defaultRoleIds() {
        SysRole user = roleService.byCode(SysRoleCode.USER);
        return user == null ? List.of() : List.of(user.getId());
    }

    /** 角色ID去重并校验存在性（允许空列表，表示不授予任何角色）。 */
    private List<Long> distinctRoleIds(List<Long> roleIds) {
        List<Long> distinct = roleIds.stream().filter(Objects::nonNull).distinct().toList();
        roleService.requireAllExists(distinct);
        return distinct;
    }

    /** 整体覆盖用户角色（先删后插）。角色数量个位数，逐条插入即可，不值得引批量写入分支。 */
    private void replaceRoles(Long userId, List<Long> roleIds) {
        userRoleMapper.delete(new LambdaQueryWrapper<SysUserRole>().eq(SysUserRole::getUserId, userId));
        if (roleIds.isEmpty()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        for (Long roleId : roleIds) {
            SysUserRole relation = new SysUserRole();
            relation.setUserId(userId);
            relation.setRoleId(roleId);
            relation.setCreatedAt(now);
            userRoleMapper.insert(relation);
        }
    }

    private SysUser requireUser(Long id) {
        SysUser user = id == null ? null : baseMapper.selectById(id);
        if (user == null) {
            throw new AiBusinessException(AiErrorCode.NOT_FOUND, "用户不存在：id=" + id);
        }
        return user;
    }

    private void requireUniqueUsername(String username, Long selfId) {
        if (count(new LambdaQueryWrapper<SysUser>()
                .ne(selfId != null, SysUser::getId, selfId)
                .eq(SysUser::getUsername, username)) > 0) {
            throw new AiBusinessException(AiErrorCode.CONFLICT, "登录名已存在：" + username);
        }
    }

    private static String requireUsername(String username) {
        String trimmed = trimToNull(username);
        if (trimmed == null) {
            throw new AiBusinessException(AiErrorCode.BAD_REQUEST, "登录名不能为空");
        }
        if (!USERNAME_PATTERN.matcher(trimmed).matches()) {
            throw new AiBusinessException(AiErrorCode.BAD_REQUEST,
                    "登录名需为 2~64 位字母、数字、下划线、点、短横线或 @");
        }
        return trimmed;
    }

    private static int requireStatus(Integer status) {
        if (status == null || (status != 0 && status != 1)) {
            throw new AiBusinessException(AiErrorCode.BAD_REQUEST, "状态只能为 1（启用）或 0（停用）");
        }
        return status;
    }

    private static int defaultStatus(Integer status) {
        return status == null ? 1 : status;
    }

    /** 拼装对外视图：角色名称在后端拼好，前端不再做 id→名称映射。 */
    private static SysUserVO assemble(SysUser user, List<Long> roleIds, Map<Long, SysRole> roleMap) {
        List<SysRole> roles = roleIds.stream().map(roleMap::get).filter(Objects::nonNull).toList();
        SysUserVO vo = new SysUserVO();
        vo.setId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setNickname(user.getNickname());
        vo.setEmail(user.getEmail());
        vo.setStatus(user.getStatus());
        vo.setLastLoginAt(user.getLastLoginAt());
        vo.setCreatedAt(user.getCreatedAt());
        vo.setRoleIds(roleIds);
        vo.setRoleCodes(roles.stream().map(SysRole::getCode).toList());
        vo.setRoleNames(roles.stream().map(SysRole::getName).collect(Collectors.joining("、")));
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
