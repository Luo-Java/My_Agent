package org.luo.ai.controller;

import org.luo.ai.dto.SaveScheduledTaskRequest;
import org.luo.ai.entity.ScheduledTask;
import org.luo.ai.service.ScheduledTaskService;
import org.luo.system.security.AuthContext;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 定时任务接口：GET /api/schedule（列表）、POST /api/schedule（新建/修改）、DELETE /api/schedule/{id}、
 * PUT /api/schedule/{id}/toggle（启用停用）、POST /api/schedule/{id}/run（立即执行一次）。
 * <p>
 * <b>只要求登录、不校验角色</b>：任务是<b>个人资产</b>（每个用户自己的周期任务），与教务模块同理，
 * 按 {@code user_id} 隔离即可 —— 越权访问他人任务一律 404（与「不存在」不可区分）。
 * <p>
 * {@code run} 会发起真实模型调用（任务本身就是一次对话），但触发者是本人、花的是本人的额度，
 * 与本类的其余接口同属「个人资源操作」，不另设 ADMIN 门槛。
 */
@RestController
@RequestMapping("/api/schedule")
public class ScheduledTaskController {

    private final ScheduledTaskService scheduledTaskService;

    public ScheduledTaskController(ScheduledTaskService scheduledTaskService) {
        this.scheduledTaskService = scheduledTaskService;
    }

    /** 本人的全部定时任务（含已停用）。 */
    @GetMapping
    public List<ScheduledTask> list() {
        return scheduledTaskService.list(AuthContext.require().id());
    }

    /** 新建（无 id）或修改（带 id）。cron 非法直接 400。 */
    @PostMapping
    public ScheduledTask save(@RequestBody SaveScheduledTaskRequest req) {
        return scheduledTaskService.save(req, AuthContext.require().id());
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        scheduledTaskService.delete(id, AuthContext.require().id());
    }

    /** 启用 / 停用。 */
    @PutMapping("/{id}/toggle")
    public ScheduledTask toggle(@PathVariable Long id, @RequestParam boolean enabled) {
        return scheduledTaskService.toggle(id, enabled, AuthContext.require().id());
    }

    /** 立即执行一次（不影响既定排期）。 */
    @PostMapping("/{id}/run")
    public ScheduledTask runNow(@PathVariable Long id) {
        return scheduledTaskService.runNow(id, AuthContext.require().id());
    }
}
