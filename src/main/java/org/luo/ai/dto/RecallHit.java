package org.luo.ai.dto;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 一条跨会话召回命中（历史消息级溯源），只服务「展示 + 注入」：让用户看到本轮回答参考了<b>哪个会话、哪一轮</b>，
 * 从而把「模型记得」与「模型编的」区分开。
 * <p>
 * 与 {@link KbCitation} 的分工：后者指向知识库块（外部资料），本记录指向<b>用户自己的历史对话</b>；
 * 两者都不参与记忆读取、对记忆窗口零影响。<b>不落库</b> —— 召回结果只在本轮有效（SSE {@code recall} 事件），
 * 刷新后不可回看；不像 citations 那样另开一列，是因为同一会话不同轮次的召回结果完全不同，存下来只会得到一堆
 * 只对那一轮有意义的快照，价值低于维护成本（本轮实际用到的文本已作为上下文注入过模型）。
 * 序列化 / 反序列化收在本记录里（{@link #toJson}/{@link #parse}），JSON 用 Hutool。
 *
 * @param index             序号（从 1 开始，与注入文本里的行首编号一致）
 * @param conversationId    命中消息所属会话 ID
 * @param conversationTitle 该会话标题（帮用户认出「是在哪次聊天里说的」）
 * @param role              消息角色：{@code user}=用户说过 / {@code assistant}=助手说过
 * @param createdAt         该消息的写入时间（给「多久以前」一个锚点）
 * @param snippet           命中内容片段（已按 {@code agent.cross-session.snippet-chars} 截断）
 * @param hitCount          命中的关键词个数（排序依据，也便于用户判断相关度）
 */
public record RecallHit(int index, String conversationId, String conversationTitle, String role,
                        LocalDateTime createdAt, String snippet, int hitCount) {

    /** 时间展示格式（只到分钟：召回片段的时间精度不需要秒）。 */
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /**
     * 序列化为 JSON 数组字符串（SSE 事件用）。
     *
     * @param hits 命中列表；null/空返回空串（调用方据此跳过推送）
     */
    public static String toJson(List<RecallHit> hits) {
        if (hits == null || hits.isEmpty()) return "";
        JSONArray arr = new JSONArray(hits.size());
        for (RecallHit h : hits) {
            JSONObject o = new JSONObject();
            o.set("index", h.index());
            o.set("conversationId", h.conversationId());
            o.set("conversationTitle", h.conversationTitle());
            o.set("role", h.role());
            o.set("createdAt", h.createdAt() == null ? null : h.createdAt().format(TIME_FMT));
            o.set("snippet", h.snippet());
            o.set("hitCount", h.hitCount());
            arr.add(o);
        }
        return arr.toString();
    }

    /** 解析 JSON 数组字符串；空/异常一律返回空列表（容错到「不抛」：展示层不应因一条脏数据整体失败）。 */
    public static List<RecallHit> parse(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            JSONArray arr = JSONUtil.parseArray(json);
            List<RecallHit> out = new ArrayList<>(arr.size());
            for (int i = 0; i < arr.size(); i++) {
                JSONObject o = arr.getJSONObject(i);
                String time = o.getStr("createdAt");
                out.add(new RecallHit(
                        o.getInt("index", i + 1),
                        o.getStr("conversationId"),
                        o.getStr("conversationTitle"),
                        o.getStr("role"),
                        time == null || time.isBlank() ? null : LocalDateTime.parse(time, TIME_FMT),
                        o.getStr("snippet"),
                        o.getInt("hitCount", 0)));
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }
}
