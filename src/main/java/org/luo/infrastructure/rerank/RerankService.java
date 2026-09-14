package org.luo.infrastructure.rerank;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.luo.config.RagProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 精排（Rerank）服务：对向量粗排召回的候选做一次相关性精排，显著提升注入提示词的资料质量。
 * <p>
 * <b>为什么需要精排</b>：向量检索是「双塔」式的——问题与知识块各自独立编码后比距离，对「关键词碰巧相近
 * 但语义无关」的块天然分不清。精排是「交叉编码」模型，把 query 与候选<b>一起</b>过模型打分，判相关性远比
 * 余弦精准——典型做法是「向量召回 20 条 → 精排取 3 条」。
 * <p>
 * <b>为什么手写 HTTP</b>：Spring AI 2.0 没有 Rerank 抽象，而 DashScope 的 text-rerank 不在 OpenAI 兼容
 * 路径下（{@code /api/v1/services/rerank/...}），无法复用自动装配的 ChatModel/EmbeddingModel。故用 Hutool
 * {@code HttpRequest} 直调（与 ChromaConnection 一致），api-key 复用 {@code spring.ai.openai.api-key}。
 * <p>
 * <b>绝不阻断检索</b>：未开启 / 未配 key / 网络异常 / 非 200 / 响应结构不符一律返回 {@code null}，
 * 由 {@link org.luo.service.KbSearchService} 降级为「按向量分截断」。精排是<b>增强</b>，不是依赖。
 * <p>
 * <b>阈值口径</b>：精排的 {@code relevance_score} 是 0~1 相关度，与余弦相似度<b>不同尺度</b>，
 * 故其下限（{@code agent.rag.rerank-min-score}）与向量分下限（{@code agent.rag.min-score}）是两套独立配置。
 */
@Slf4j
@Service
public class RerankService {

    private final RagProperties props;
    /** DashScope API Key（复用 chat/embedding 的同一把；为空则视为精排不可用）。 */
    private final String apiKey;

    public RerankService(RagProperties props, @Value("${spring.ai.openai.api-key:}") String apiKey) {
        this.props = props;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        log.info("RerankService 初始化：精排开关={}，模型={}，有可用Key={}（未配置 key 时自动降级为向量分）",
                props.rerankOn(), props.rerankModel(), !this.apiKey.isEmpty());
    }

    /** 精排是否在途可用：配置开启且拿到了 api-key。 */
    public boolean available() {
        return props.rerankOn() && !apiKey.isEmpty();
    }

    /**
     * 对候选文档精排，返回<b>按相关度降序</b>的 {@code (候选原索引, 相关度分)}。index 是入参
     * {@code documents} 的下标，调用方据此把分数映射回原始命中对象；{@code topN} 透传给 DashScope 做服务端截断。
     *
     * @param query     用户问题
     * @param documents 候选文档正文（与粗排命中一一对应，顺序即索引）
     * @return 排序后的精排结果；不可用 / 失败返回 {@code null}（调用方降级为向量分）
     */
    public List<Ranked> rerank(String query, List<String> documents, int topN) {
        if (!available() || query == null || query.isBlank() || documents == null || documents.isEmpty()) {
            return null;
        }
        try {
            JSONObject input = new JSONObject()
                    .set("query", query)
                    .set("documents", documents);
            // 显式 <String, Object>：两个值类型不同（Boolean / Integer），否则泛型推断为交叉类型无法赋给 Object 值映射
            Map<String, Object> parameters = Map.<String, Object>of(
                    "return_documents", false,
                    "top_n", Math.max(1, topN));
            JSONObject body = new JSONObject()
                    .set("model", props.rerankModel())
                    .set("input", input)
                    .set("parameters", parameters);

            try (HttpResponse resp = HttpRequest.post(props.rerankUrl())
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .body(body.toString())
                    .timeout(props.timeoutMillis())
                    .execute()) {
                if (!resp.isOk()) {
                    log.warn("精排调用失败（降级为向量分）：HTTP {}，响应={}", resp.getStatus(), chop(resp.body()));
                    return null;
                }
                return parse(resp.body());
            }
        } catch (Exception e) {
            log.warn("精排调用异常（降级为向量分）：{}", e.getMessage());
            return null;
        }
    }

    /** 解析 DashScope text-rerank 响应：{@code output.results[].{index, relevance_score}}。 */
    private List<Ranked> parse(String body) {
        JSONObject output = JSONUtil.parseObj(body).getJSONObject("output");
        JSONArray results = (output == null) ? null : output.getJSONArray("results");
        if (results == null || results.isEmpty()) {
            log.warn("精排响应缺少 results（降级为向量分）：{}", chop(body));
            return List.of();
        }
        List<Ranked> ranked = new ArrayList<>(results.size());
        for (Object o : results) {
            if (!(o instanceof JSONObject r)) continue;
            Integer index = r.getInt("index");
            Double score = r.getDouble("relevance_score");
            if (index == null || score == null) continue;
            ranked.add(new Ranked(index, score));
        }
        return ranked;
    }

    /** 日志截断：防止超长响应体刷屏。 */
    private static String chop(String text) {
        if (text == null) return "";
        return text.length() > 300 ? text.substring(0, 300) + "..." : text;
    }

    /**
     * 一条精排结果。
     *
     * @param index 候选在入参 documents 中的下标（用于回溯原始命中对象）
     * @param score 相关度（0~1，越大越相关）
     */
    public record Ranked(int index, double score) {
    }
}
