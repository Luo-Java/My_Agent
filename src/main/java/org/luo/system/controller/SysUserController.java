package org.luo.system.controller;

import jakarta.annotation.Resource;
import org.luo.common.result.PageResult;
import org.luo.common.result.RestResult;
import org.luo.system.constant.SysRoleCode;
import org.luo.system.dto.ResetPasswordRequest;
import org.luo.system.dto.SaveUserRequest;
import org.luo.system.dto.SysUserDTO;
import org.luo.system.dto.UpdateUserRequest;
import org.luo.system.security.RequireRole;
import org.luo.system.service.SysUserService;
import org.luo.system.vo.SysUserVO;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户管理接口（/api/user）：命令式风格，与教务接口一致。整个控制器限定 ADMIN 角色。
 * <ul>
 *   <li>{@code POST /page} —— 分页，筛选条件（关键词/状态/角色）走 body</li>
 *   <li>{@code GET /{id}} —— 单条详情</li>
 *   <li>{@code POST /save} —— 新增</li>
 *   <li>{@code PUT /update} —— 编辑，id 在 body</li>
 *   <li>{@code PUT /{id}/password} —— 重置他人口令</li>
 *   <li>{@code DELETE /delete/{id}} —— 删除</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/user")
@RequireRole(SysRoleCode.ADMIN)
public class SysUserController {

    @Resource
    private SysUserService service;

    @PostMapping("/page")
    public RestResult<PageResult<SysUserVO>> page(@RequestBody SysUserDTO dto) {
        return RestResult.ok(service.page(dto));
    }

    @GetMapping("/{id}")
    public RestResult<SysUserVO> get(@PathVariable Long id) {
        return RestResult.ok(service.detail(id));
    }

    @PostMapping("/save")
    public RestResult<SysUserVO> save(@RequestBody SaveUserRequest req) {
        return RestResult.ok(service.saveUser(req));
    }

    @PutMapping("/update")
    public RestResult<SysUserVO> update(@RequestBody UpdateUserRequest req) {
        return RestResult.ok(service.updateUser(req));
    }

    @PutMapping("/{id}/password")
    public RestResult<Void> resetPassword(@PathVariable Long id, @RequestBody ResetPasswordRequest req) {
        service.updatePassword(id, req.getPassword());
        return RestResult.ok();
    }

    @DeleteMapping("/delete/{id}")
    public RestResult<Void> delete(@PathVariable Long id) {
        service.deleteUser(id);
        return RestResult.ok();
    }
}
