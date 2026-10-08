package org.luo.ai.dto;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 澄清追问（参数补全）的显式状态：一个会话处于「追问中」时才有，落在 {@code conversation.clarify_state}。
 * <p>
 * <b>为什么要有它</b>：此前「已追问几次 / 已确认哪些参数 / 原始请求是什么」全靠每轮扫最近
 * {@code HISTORY_SCAN_LIMIT} 条 {@code chat_message} 重放推导。这在三种情况下会算歪 ——
 * ① 历史超窗被摘要压缩，原始请求那条消息滑出窗口，「任务范围」丢失；
 * ② 消息被用户标「不参与记忆」，重放侧看不到，追问次数凭空少一次；
 * ③ 长会话里最后一轮正式回答落在扫描范围之外，连续追问段的起点判断不到。
 * 落一列显式状态后这三项直接读，不再猜。<b>历史重放作为兜底保留</b>（老会话没有这一列，行为不回退）。
 * <p>
 * <b>{@link #agentId} 是「换智能体即作废」的判据</b>：路由转向别的智能体时本状态自然失效，
 * 不需要额外的清理动作（见 {@link #belongsTo}）。
 *
 * @param agentId 这份追问属于哪个智能体（关联 agent.id）；为空表示来源不明，一律不认
 * @param asked   已追问次数（0 = 尚未追问）。达到 {@link #MAX_ASKED} 即转交主模型尽力执行、不再追问
 * @param request 触发本次追问的<b>原始用户请求</b>：参数抽取的锚点，即使它已被摘要压缩出窗口也不丢
 * @param question 最近一次追问的原文：供编排层做话题切换预检（判断本轮消息是「回答追问」还是「开新话题」）
 * @param params  已确认参数快照（key = paramSchema 的 key，value = 用户明确给出的取值）
 */
@Slf4j
public record ClarifyState(Long agentId, int asked, String request, String question, Map<String, String> params) {

    /**
     * 单轮对话最多追问次数（达到后转主模型尽力执行，不再追问）。
     * <p>
     * <b>唯一来源</b>：{@code ParamFillingService} 生成追问文案时用它，会话列表给前端展示「2/3」时也用它 ——
     * 阈值分散成两处必然漂移，而漂移的表现是「界面说还能问，实际已经转主模型了」。
     */
    public static final int MAX_ASKED = 3;

    public ClarifyState {
        params = (params == null || params.isEmpty()) ? Map.of() : Map.copyOf(params);
    }

    /** 该状态是否属于给定智能体（换智能体即作废）。agentId 为空 / 入参为空一律为 false。 */
    public boolean belongsTo(Long otherAgentId) {
        return agentId != null && otherAgentId != null && agentId.equals(otherAgentId);
    }

    /**
     * 本轮又问了一次：次数 +1，参数累积（新值覆盖旧值），并锚定原始请求与本次追问原文。
     *
     * @param originalRequest 原始请求：首次追问取本轮用户消息，后续沿用已有的锚点
     */
    public ClarifyState afterAsk(String question, Map<String, String> mergedParams, String originalRequest) {
        return new ClarifyState(agentId, asked + 1, originalRequest, question, mergedParams);
    }

    /**
     * 解析列里的 JSON。空列 → {@code null}（没有进行中的追问）。
     * <p>
     * <b>解析失败也返回 {@code null} 并 WARN</b>，不抛错、也不返回一个「空状态」：坏数据与「没有追问」
     * 在调用侧都归结为「重新开始一段追问」，但会留下日志；若抛出异常，一条坏 JSON 会让整轮对话 500，
     * 代价远大于从零重问。
     */
    public static ClarifyState parse(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            JSONObject o = JSONUtil.parseObj(json);
            Map<String, String> params = new LinkedHashMap<>();
            JSONObject p = o.getJSONObject("params");
            if (p != null) {
                for (String k : p.keySet()) {
                    String v = p.getStr(k);
                    if (v != null && !v.isBlank()) params.put(k, v);
                }
            }
            return new ClarifyState(o.getLong("agentId"), o.getInt("asked", 0),
                    o.getStr("request"), o.getStr("question"), params);
        } catch (Exception e) {
            log.warn("澄清状态解析失败（按「无追问」处理，将从零重新追问）：{}", e.getMessage());
            return null;
        }
    }

    /** 序列化写入列（项目约定：LLM / 内部 JSON 一律 Hutool，不用 Jackson）。 */
    public String toJson() {
        JSONObject o = new JSONObject();
        o.set("agentId", agentId);
        o.set("asked", asked);
        o.set("request", request);
        o.set("question", question);
        o.set("params", params == null ? Map.of() : params);
        return o.toString();
    }
}
