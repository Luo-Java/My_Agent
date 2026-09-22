package org.luo.system.controller;

import jakarta.annotation.Resource;
import org.luo.common.result.PageResult;
import org.luo.common.result.RestResult;
import org.luo.system.constant.SysRoleCode;
import org.luo.system.dto.SaveRoleRequest;
import org.luo.system.dto.SysRoleDTO;
import org.luo.system.dto.UpdateRoleRequest;
import org.luo.system.security.RequireRole;
import org.luo.system.service.SysRoleService;
import org.luo.system.vo.SysRoleVO;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 角色管理接口（/api/role）：命令式风格。整个控制器限定 ADMIN 角色。
 * <ul>
 *   <li>{@code POST /page} —— 分页</li>
 *   <li>{@code GET /list} —— 全部角色（用户表单的角色多选、筛选下拉用）</li>
 *   <li>{@code POST /save} —— 新增</li>
 *   <li>{@code PUT /update} —— 编辑，id 在 body（编码不可改）</li>
 *   <li>{@code DELETE /delete/{id}} —— 删除，被用户引用时 409</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/role")
@RequireRole(SysRoleCode.ADMIN)
public class SysRoleController {

    @Resource
    private SysRoleService service;

    @PostMapping("/page")
    public RestResult<PageResult<SysRoleVO>> page(@RequestBody SysRoleDTO dto) {
        return RestResult.ok(service.page(dto));
    }

    @GetMapping("/list")
    public RestResult<List<SysRoleVO>> list() {
        return RestResult.ok(service.listAll());
    }

    @PostMapping("/save")
    public RestResult<SysRoleVO> save(@RequestBody SaveRoleRequest req) {
        return RestResult.ok(service.saveRole(req));
    }

    @PutMapping("/update")
    public RestResult<SysRoleVO> update(@RequestBody UpdateRoleRequest req) {
        return RestResult.ok(service.updateRole(req));
    }

    @DeleteMapping("/delete/{id}")
    public RestResult<Void> delete(@PathVariable Long id) {
        service.deleteRole(id);
        return RestResult.ok();
    }
}
