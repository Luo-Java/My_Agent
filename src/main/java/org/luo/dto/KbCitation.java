package org.luo.dto;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * 一条 RAG 引用来源（知识块级溯源），只服务「展示」：让用户看到回答里的 {@code [1]} 角标来自哪个库的
 * 哪个文件，从而把「模型自己编的」与「文档里写的」区分开。
 * <p>
 * 生命周期与附件元数据同构——落库在独立列 {@code chat_message.citations_json}（仅 assistant 消息），
 * <b>不参与记忆读取</b>（DbChatMemory.get 只读 content），对 LLM 上下文与 token 零影响。
 * <p>
 * 序列化 / 反序列化收在本记录里（{@link #toJson}/{@link #parse}）：同一份 JSON 有三处消费方——历史展示、
 * agent_trace 可观测、SSE citations 事件实时展示，放一处才能保证三处格式永远一致。JSON 用 Hutool（不用 Jackson）。
 *
 * @param index   引用序号（从 1 开始，与正文 [n] 角标、资料块 [n] 行首编号一致）
 * @param chunkId 命中的知识块 ID（kb_chunk.id）
 * @param kbId    所属知识库 ID（kb.id）
 * @param kbName  知识库名称（展示用，如「通用知识库」）
 * @param source  来源标注（通常是文件名，关联 kb_file.file_name；可能为空）
 * @param score   相关度分：启用精排时为精排 relevance_score（0~1），降级时为真实余弦相似度
 */
public record KbCitation(int index, Long chunkId, Long kbId, String kbName, String source, double score) {

    /**
     * 序列化为 JSON 数组字符串（落库 / SSE 事件用）。
     *
     * @param citations 引用列表；null/空返回空串（调用方据此跳过落库）
     */
    public static String toJson(List<KbCitation> citations) {
        if (citations == null || citations.isEmpty()) return "";
        JSONArray arr = new JSONArray(citations.size());
        for (KbCitation c : citations) {
            JSONObject o = new JSONObject();
            o.set("index", c.index());
            o.set("chunkId", c.chunkId());
            o.set("kbId", c.kbId());
            o.set("kbName", c.kbName());
            o.set("source", c.source());
            o.set("score", c.score());
            arr.add(o);
        }
        return arr.toString();
    }

    /**
     * 解析 JSON 数组字符串；空/异常一律返回空列表。容错到「不抛」是刻意的：历史读取与追踪展示都不应因
     * 一条脏数据整体失败。
     */
    public static List<KbCitation> parse(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            JSONArray arr = JSONUtil.parseArray(json);
            List<KbCitation> out = new ArrayList<>(arr.size());
            for (int i = 0; i < arr.size(); i++) {
                JSONObject o = arr.getJSONObject(i);
                out.add(new KbCitation(
                        o.getInt("index", i + 1),
                        o.getLong("chunkId"),
                        o.getLong("kbId"),
                        o.getStr("kbName"),
                        o.getStr("source"),
                        o.getDouble("score", 0.0)));
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }
}
