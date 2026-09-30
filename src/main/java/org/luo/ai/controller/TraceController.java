package org.luo.ai.controller;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import org.luo.ai.dto.KbCitation;
import org.luo.ai.dto.TraceDto;
import org.luo.ai.entity.AgentTrace;
import org.luo.ai.service.ConversationService;
import org.luo.ai.trace.TraceService;
import org.luo.common.exception.AiBusinessException;
import org.luo.common.exception.AiErrorCode;
import org.luo.system.constant.SysRoleCode;
import org.luo.system.security.AuthContext;
import org.luo.system.security.LoginUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * 链路追踪查询接口（可观测性，只读）。用于回答「这轮为什么路由到它」「规划器排了哪几步」「RAG 有没有命中」
 * 「调了哪些工具、花了多少 token、耗时多久」。数据由 {@link TraceService} 在本轮回复产出后异步落库，本接口不参与写入。
 * <p>
 * GET /api/trace（列表，可按 conversationId 过滤，时间倒序）、GET /api/trace/{traceId}（单轮详情）；
 * 走统一 {@code /api/**} 登录鉴权（JwtAuthInterceptor）。
 * <p>
 * <b>可见性</b>：默认只回「当前登录用户名下会话」的记录 —— 不传 conversationId 表示不限会话，但<b>不是</b>
 * 「不限用户」。持有 {@code ADMIN} 角色的账号走全量视角，可以看到所有人的追踪（运维排查用）。归属判定在
 * SQL 层由 {@code JOIN conversation} 完成，见 {@code AgentTraceMapper.xml}。
 * <p>
 * 身份一律在 HTTP 线程用 {@link AuthContext#require()} 取出后当参数传给服务层：本接口虽是同步的，
 * 但保持与对话链路一致的「谁的身份谁传下去」写法，避免日后有人把它改成流式时踩 ThreadLocal 失效的坑。
 */
@RestController
@RequestMapping("/api/trace")
public class TraceController {

    private final TraceService traceService;
    private final ConversationService conversationService;

    public TraceController(TraceService traceService, ConversationService conversationService) {
        this.traceService = traceService;
        this.conversationService = conversationService;
    }

    /**
     * 查询追踪记录（时间倒序）。
     *
     * @param conversationId 可选。传了则只回该会话的记录；不传表示「不限会话」——
     *                       普通用户仍只限自己名下会话，管理员则是全站。
     * @param limit          条数；默认 50、上限 200
     */
    @GetMapping
    public List<TraceDto> list(@RequestParam(required = false) String conversationId,
                              @RequestParam(required = false) Integer limit) {
        LoginUser me = AuthContext.require();
        boolean allUsers = me.hasRole(SysRoleCode.ADMIN);
        // 指定了会话且非管理员：先校验会话归属，不是自己的直接 404。放在这一层而不是靠「返回空列表」，
        // 是为了让「会话不是你的」与「这个会话还没产生追踪」可区分 —— 后者是正常空态，不该报错。
        if (!allUsers && conversationId != null && !conversationId.isBlank()) {
            conversationService.checkAccess(conversationId, me.id());
        }
        return traceService.list(conversationId, limit, me.id(), allUsers).stream()
                .map(TraceController::toDto)
                .toList();
    }

    /**
     * 查询单轮追踪详情。
     * <p>
     * 不存在或不属于当前用户（非管理员）一律 404 —— 二者不可区分，避免拿 traceId 探测他人记录是否存在。
     * 此前返回「200 + null」，加了归属校验后不能再保留：那会让探测者从「有记录但读不到」与「没记录」
     * 的差异里套出信息。
     */
    @GetMapping("/{traceId}")
    public TraceDto get(@PathVariable String traceId) {
        LoginUser me = AuthContext.require();
        AgentTrace t = traceService.get(traceId, me.id(), me.hasRole(SysRoleCode.ADMIN));
        if (t == null) {
            throw new AiBusinessException(AiErrorCode.NOT_FOUND, "追踪记录不存在");
        }
        return toDto(t);
    }

    /** 实体 → 视图：把两个 JSON 字符串列解析为结构化列表，解析失败按空列表降级（不因脏数据整体失败）。 */
    private static TraceDto toDto(AgentTrace t) {
        return new TraceDto(
                t.getTraceId(), t.getConversationId(), t.getMode(), t.getRouteSource(), t.getAgentCode(),
                t.getUserMessage(), t.getRetrievalQuery(), t.getPlanJson(), parseToolCalls(t.getToolCalls()),
                t.getKbHitCount() == null ? 0 : t.getKbHitCount(),
                KbCitation.parse(t.getCitationsJson()),
                t.getPromptTokens() == null ? 0 : t.getPromptTokens(),
                t.getCompletionTokens() == null ? 0 : t.getCompletionTokens(),
                t.getTotalTokens() == null ? 0 : t.getTotalTokens(),
                t.getElapsedMs() == null ? 0L : t.getElapsedMs(),
                t.getStatus(), t.getErrorMessage(), t.getCreatedAt());
    }

    /** 工具调用明细 JSON → 列表；空/异常返回空列表。 */
    private static List<TraceDto.ToolCallDto> parseToolCalls(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            JSONArray arr = JSONUtil.parseArray(json);
            List<TraceDto.ToolCallDto> out = new ArrayList<>(arr.size());
            for (int i = 0; i < arr.size(); i++) {
                JSONObject o = arr.getJSONObject(i);
                out.add(new TraceDto.ToolCallDto(o.getStr("name"), o.getStr("args"), o.getStr("result")));
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }
}
