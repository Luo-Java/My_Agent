package org.luo.controller;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import org.luo.dto.KbCitation;
import org.luo.dto.TraceDto;
import org.luo.entity.AgentTrace;
import org.luo.trace.TraceService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * 链路追踪查询接口（可观测性）。
 * <p>
 * 只读，用于回答「这轮为什么路由到它」「规划器排了哪几步」「RAG 有没有命中」「调了哪些工具、花了多少 token、
 * 耗时多久」。数据本身由 {@link TraceService} 在本轮回复产出后异步落库，本接口不参与写入。
 * <p>
 * GET /api/trace                      - 追踪列表（可按 conversationId 过滤，按时间倒序）
 * GET /api/trace/{traceId}            - 单轮详情
 * <p>
 * 走统一 {@code /api/**} 鉴权（ApiKeyInterceptor），与其它业务接口一致。
 */
@RestController
@RequestMapping("/api/trace")
public class TraceController {

    private final TraceService traceService;

    public TraceController(TraceService traceService) {
        this.traceService = traceService;
    }

    /**
     * 查询追踪记录，按时间倒序。
     *
     * @param conversationId 会话 ID（可选；不传则查全部会话，调试用）
     * @param limit          条数（可选，默认 50，上限 200）
     * @return 追踪列表（JSON 字段已解析为结构化列表）
     */
    @GetMapping
    public List<TraceDto> list(@RequestParam(required = false) String conversationId,
                              @RequestParam(required = false) Integer limit) {
        return traceService.list(conversationId, limit).stream().map(TraceController::toDto).toList();
    }

    /**
     * 查询单轮追踪详情。
     *
     * @param traceId 本轮追踪 ID
     * @return 详情；不存在返回 null（前端按 200 + null 处理即可，调试接口不做 404 语义）
     */
    @GetMapping("/{traceId}")
    public TraceDto get(@PathVariable String traceId) {
        AgentTrace t = traceService.get(traceId);
        return t == null ? null : toDto(t);
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
