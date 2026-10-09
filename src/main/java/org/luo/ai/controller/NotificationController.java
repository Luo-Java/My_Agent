package org.luo.ai.controller;

import org.luo.ai.entity.Notification;
import org.luo.ai.service.NotificationService;
import org.luo.system.security.AuthContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 通知接口：GET /api/notification（最近若干条，可只看未读）、GET /api/notification/unread（未读数）、
 * POST /api/notification/{id}/read、POST /api/notification/read-all。
 * <p>
 * <b>只要求登录</b>：每条通知本身就按 {@code user_id} 隔离（NULL = 全员广播），无需角色门槛。
 * <p>
 * 前端以轮询为主（间隔可自定）—— 通知的产生时机与任何 HTTP 请求无关（后台线程 / 定时器），
 * 没有可依附的长连接，轮询一张表是最简单且可重放的方案。
 */
@RestController
@RequestMapping("/api/notification")
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    /** 本人可见的通知（含广播），时间倒序，最多 50 条。{@code unreadOnly=true} 时只回未读。 */
    @GetMapping
    public List<Notification> list(@RequestParam(required = false) Boolean unreadOnly) {
        return notificationService.list(AuthContext.require().id(), Boolean.TRUE.equals(unreadOnly));
    }

    /** 未读数（前端铃铛角标）。 */
    @GetMapping("/unread")
    public Map<String, Object> unread() {
        return Map.of("count", notificationService.unreadCount(AuthContext.require().id()));
    }

    /** 标记单条已读；不存在 / 非本人可见 / 已读均返回 {@code ok=false}（不报错，幂等语义）。 */
    @PostMapping("/{id}/read")
    public Map<String, Object> markRead(@PathVariable Long id) {
        return Map.of("ok", notificationService.markRead(id, AuthContext.require().id()));
    }

    /** 全部标记已读，返回本次标记条数。 */
    @PostMapping("/read-all")
    public Map<String, Object> markAllRead() {
        int n = notificationService.markAllRead(AuthContext.require().id());
        return Map.of("ok", true, "marked", n);
    }
}
