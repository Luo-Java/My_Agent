package org.luo.ai.service;

import cn.hutool.crypto.digest.DigestUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.extern.slf4j.Slf4j;
import org.luo.ai.dto.ConversationFactDto;
import org.luo.ai.entity.ConversationFact;
import org.luo.ai.mapper.ConversationFactMapper;
import org.luo.common.exception.AiBusinessException;
import org.luo.common.exception.AiErrorCode;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 会话长期事实条目服务：逐条事实的读写、注入文本渲染，以及「按一次合并产出重写自动条目」。
 * <p>
 * <b>职责边界</b>：本类只碰 {@code conversation_fact} 一张表、<b>不做归属校验</b> —— 校验由 Controller 调
 * {@code ConversationService.requireOwned} 完成后按 conversationId 调进来。原因是依赖方向：
 * {@code ConversationService} 需要在「重置记忆 / 清空消息 / 删会话」时清理条目，若本类反过来依赖它就会成环。
 * <p>
 * 红线（本类核心不变量）：两种来源待遇不同 —— {@code SOURCE_MERGE} 每次合并按 diff 重写，<b>模型这次没输出的
 * 条目视为过时并删除</b>（这是淘汰旧事实的唯一通路，见 {@link #merge}）；{@code SOURCE_USER} <b>绝不覆盖也绝不
 * 删除</b>（用户明确写下的东西不该被一次自动整理悄悄抹掉），且用户手改过的条目会从 MERGE <b>转为</b> USER
 * （见 {@link #update}）。
 */
@Slf4j
@Service
public class ConversationFactService {

    /** 注入 prompt 时的块头（与后随的「历史摘要」块对称）。 */
    private static final String INJECT_HEADER =
            "\n\n[长期事实] 以下是此前对话中逐条确认的长期信息（按主题分组），回答时应优先考虑并遵守：\n";

    /** 旧版归档的块头：与功能 E 之前一字不差，存量会话注入的仍是同一段文本，模型看到的东西没变。 */
    private static final String LEGACY_INJECT_HEADER =
            "\n\n[长期核心信息] 以下内容来自长期记忆（姓名/身份/偏好/待办等），回答时应优先考虑并遵守：\n";

    /** 模型表达「没有事实」的几种写法（整段为「无」时不能当成一条事实名叫「无」的条目）。 */
    private static final Set<String> EMPTY_TOKENS = Set.of("无", "（无）", "(无)", "-", "none");

    private final ConversationFactMapper mapper;

    public ConversationFactService(ConversationFactMapper mapper) {
        this.mapper = mapper;
    }

    // ==================================================================
    // 读
    // ==================================================================

    /**
     * 按会话列出全部条目。<b>按自增 id 正序</b>（写入顺序）而不是按 {@code updated_at}：
     * 后者每次合并都会刷新，面板顺序会随合并抖动；顺序稳定比「最近维护的在前」更重要。
     */
    public List<ConversationFact> list(String conversationId) {
        if (isBlank(conversationId)) return List.of();
        return mapper.selectList(new LambdaQueryWrapper<ConversationFact>()
                .eq(ConversationFact::getConversationId, conversationId)
                .orderByAsc(ConversationFact::getId));
    }

    /** 同上，转页面视图。 */
    public List<ConversationFactDto> listDto(String conversationId) {
        return list(conversationId).stream().map(ConversationFactDto::of).toList();
    }

    /**
     * 注入 prompt 的<b>长期事实段</b>：条目非空则用条目（带块头 + 主题分组），条目为空则回退旧版
     * {@code core_facts} 原文（也用 E 之前那块一模一样的块头）。
     * <p>
     * <b>「条目优先、归档兜底」这条判据只能有这一份</b>：注入侧（{@code ChatComposer}）与追踪采集侧
     * （{@code ChatService}）都要用它 —— 追踪里报的字符数必须是<b>真实注入的那一段</b>。两处各写一遍，
     * 迟早出现「弹窗说注入了 300 字、实际一个字没注入」这类对不上的假数据。
     *
     * @param legacyCoreFacts 旧版归档原文（{@code conversation.core_facts}），可为 null
     * @return 空串 = 两处都没内容（这一轮没有事实段）
     */
    public String injectableFactsText(String conversationId, String legacyCoreFacts) {
        String lines = linesText(conversationId);
        if (!lines.isEmpty()) {
            return INJECT_HEADER + lines;
        }
        if (isBlank(legacyCoreFacts)) return "";
        return LEGACY_INJECT_HEADER + legacyCoreFacts;
    }

    /**
     * 合并侧喂给模型的「已有关键事实」清单：条目非空则用条目行，否则用旧版归档原文。
     * <p>
     * 与 {@link #injectableFactsText} 的<b>唯一区别是不带块头</b>，这不是洁癖：块头是一句
     * 「以下是……应优先遵守」的说明句，混在清单里会被模型当成一条事实复述回来，从而在条目表里落下一行
     * 「- [其它] 以下是此前对话中逐条确认的长期信息」—— 注入用的文本绝不能直接当清单输入。
     * <p>
     * 回退到旧归档的意义：存量会话（只有 {@code core_facts}、没有条目）首次合并时，模型会把这段旧文本
     * 拆成带 {@code [主题]} 前缀的条目，迁移就此自动完成，一行归档都不用改。
     */
    public String mergeInputText(String conversationId, String legacyCoreFacts) {
        String lines = linesText(conversationId);
        if (!lines.isEmpty()) return lines;
        return isBlank(legacyCoreFacts) ? "" : legacyCoreFacts.strip();
    }

    /** 条目渲染成「一条一行」的裸文本（不含块头）；无条目返回空串。 */
    private String linesText(String conversationId) {
        return renderLines(list(conversationId));
    }

    /**
     * 条目 → 「一条一行」文本。<b>按主题白名单顺序分组</b>输出：模型一次看到的是一簇同类信息
     * （身份 → 偏好 → 待办 → 背景 → 其它），比按写入时间穿插更易遵守，也让 prompt 里的事实段可人工扫读。
     */
    private static String renderLines(List<ConversationFact> facts) {
        if (facts == null || facts.isEmpty()) return "";
        Map<String, List<ConversationFact>> byTopic = new LinkedHashMap<>();
        for (String t : ConversationFact.TOPICS) {
            byTopic.put(t, new ArrayList<>());
        }
        for (ConversationFact f : facts) {
            byTopic.computeIfAbsent(topicOf(f.getTopic()), k -> new ArrayList<>()).add(f);
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, List<ConversationFact>> e : byTopic.entrySet()) {
            for (ConversationFact f : e.getValue()) {
                sb.append("- [").append(e.getKey()).append("] ").append(oneLine(f.getFact())).append("\n");
            }
        }
        return sb.toString();
    }

    // ==================================================================
    // 用户侧增删改（一律记为 SOURCE_USER）
    // ==================================================================

    /**
     * 手动新增一条事实。
     * <p>
     * <b>与自动条目重合时不是失败，而是「认领」</b>：用户把模型整理出来的某条事实又手写了一遍，意图是
     * 「这条我要留着」——此时把它转为 {@code USER} 来源（此后合并不再能删掉它），比报一句「已存在」更贴合
     * 用户的真实意图。已经是 {@code USER} 来源才算真的重复添加，明确报 400（重复添加不是成功操作，
     * 静默成功会让用户以为加了两条）。
     */
    public ConversationFact add(String conversationId, String topic, String fact) {
        requireConversationId(conversationId);
        String t = topicOf(topic);
        String text = requireFact(fact);
        ConversationFact exist = findByHash(conversationId, hash(t, text));
        if (exist != null) {
            if (ConversationFact.SOURCE_USER.equals(exist.getSource())) {
                throw new AiBusinessException(AiErrorCode.BAD_REQUEST, "该事实已存在（同主题同内容）");
            }
            mapper.update(null, new LambdaUpdateWrapper<ConversationFact>()
                    .eq(ConversationFact::getId, exist.getId())
                    .set(ConversationFact::getSource, ConversationFact.SOURCE_USER)
                    .set(ConversationFact::getUpdatedAt, LocalDateTime.now()));
            log.info("手动添加的事实与自动条目重合，已转为手动来源（此后合并不再淘汰它）：会话={}，条目={}",
                    conversationId, exist.getId());
            exist.setSource(ConversationFact.SOURCE_USER);
            return exist;
        }
        LocalDateTime now = LocalDateTime.now();
        ConversationFact f = new ConversationFact();
        f.setConversationId(conversationId);
        f.setTopic(t);
        f.setFact(text);
        f.setFactHash(hash(t, text));
        f.setSource(ConversationFact.SOURCE_USER);
        f.setCreatedAt(now);
        f.setUpdatedAt(now);
        mapper.insert(f);
        log.info("手动新增长期事实：会话={}，主题={}，长度={}", conversationId, t, text.length());
        return f;
    }

    /**
     * 修改一条事实（主题与内容都可改）。
     * <p>
     * <b>改过的条目一律转为 {@code USER} 来源</b>：用户为什么要改它？因为模型记错了。那么这条就不该再
     * 由模型下一次整理去覆盖或删除 —— 来源从「自动」变「手动」正是这个意思。
     */
    public ConversationFact update(Long id, String conversationId, String topic, String fact) {
        ConversationFact exist = require(id, conversationId);
        String t = topicOf(topic);
        String text = requireFact(fact);
        String h = hash(t, text);
        ConversationFact other = findByHash(conversationId, h);
        if (other != null && !other.getId().equals(exist.getId())) {
            throw new AiBusinessException(AiErrorCode.BAD_REQUEST, "已存在相同的事实条目（同主题同内容）");
        }
        boolean promoted = !ConversationFact.SOURCE_USER.equals(exist.getSource());
        mapper.update(null, new LambdaUpdateWrapper<ConversationFact>()
                .eq(ConversationFact::getId, exist.getId())
                .set(ConversationFact::getTopic, t)
                .set(ConversationFact::getFact, text)
                .set(ConversationFact::getFactHash, h)
                .set(ConversationFact::getSource, ConversationFact.SOURCE_USER)
                .set(ConversationFact::getUpdatedAt, LocalDateTime.now()));
        if (promoted) {
            log.info("自动整理条目被人工修正，已转为手动来源：会话={}，条目={}", conversationId, exist.getId());
        }
        log.info("修改长期事实：会话={}，条目={}，主题={}，长度={}", conversationId, exist.getId(), t, text.length());
        exist.setTopic(t);
        exist.setFact(text);
        exist.setFactHash(h);
        exist.setSource(ConversationFact.SOURCE_USER);
        return exist;
    }

    /**
     * 删除一条事实（自动 / 手动都可删）。
     * <p>
     * <b>删自动条目可能被下次合并重新加回来吗</b>：不会，除非新对话内容又提到它。合并的输入是
     * 「当前条目 + 新增溢出内容」，删掉的行模型看不到，自然不会复述；只有原文重新出现才会再生成一条 ——
     * 那属于「事实又被确认了一次」，是正确的行为。这一点在面板上要说明，否则用户会以为删除没生效。
     */
    public void delete(Long id, String conversationId) {
        ConversationFact exist = require(id, conversationId);
        mapper.deleteById(exist.getId());
        log.info("删除长期事实：会话={}，条目={}，主题={}", conversationId, id, exist.getTopic());
    }

    // ==================================================================
    // 合并侧（MemoryMergeService 专用）与内部清理
    // ==================================================================

    /**
     * 用一次合并的产出<b>重写自动条目</b>（{@code source=MERGE}），返回增 / 刷新 / 删的条数。
     * <p>
     * 语义是 diff 而非追加：模型这次列出的条目里没出现的自动条目即视为过时并删除 —— 这是淘汰旧事实的
     * <b>唯一</b>通路（提示词里也写明了「不输出即视为删除」）。用户手加的条目<b>既不会被删也不会被改</b>，
     * 但如果它出现在本次列表里，它的 {@code updated_at} 会照常刷新（那表示「这条仍然有效」）。
     * <p>
     * 超长条目<b>截断而不是丢弃</b>并记 WARN：内容来自模型、问不回去，丢掉整条比留个截断版更亏；但必须出声，
     * 否则「模型总在产超长条目」永远看不见。用户侧新增走的是另一条路（直接 400，见 {@link #add}）。
     */
    public MergeOutcome merge(String conversationId, List<FactLine> wanted) {
        if (isBlank(conversationId)) return new MergeOutcome(0, 0, 0);
        Map<String, ConversationFact> byHash = new HashMap<>();
        for (ConversationFact f : list(conversationId)) {
            byHash.put(f.getFactHash(), f);
        }
        LocalDateTime now = LocalDateTime.now();
        Set<String> desired = new LinkedHashSet<>();
        List<Long> refreshed = new ArrayList<>();
        int inserted = 0;
        for (FactLine line : (wanted == null ? List.<FactLine>of() : wanted)) {
            String text = oneLine(line.fact());
            if (text.isEmpty()) continue;
            if (text.length() > ConversationFact.FACT_MAX) {
                log.warn("合并产出的单条事实超长（{} 字 > {}），已截断：会话={}", text.length(),
                        ConversationFact.FACT_MAX, conversationId);
                text = text.substring(0, ConversationFact.FACT_MAX);
            }
            String t = topicOf(line.topic());
            String h = hash(t, text);
            if (!desired.add(h)) continue;      // 模型把同一句列了两遍：只留一条
            ConversationFact hit = byHash.get(h);
            if (hit == null) {
                ConversationFact f = new ConversationFact();
                f.setConversationId(conversationId);
                f.setTopic(t);
                f.setFact(text);
                f.setFactHash(h);
                f.setSource(ConversationFact.SOURCE_MERGE);
                f.setCreatedAt(now);
                f.setUpdatedAt(now);
                mapper.insert(f);
                inserted++;
            } else {
                refreshed.add(hit.getId());
            }
        }
        int touched = 0;
        if (!refreshed.isEmpty()) {
            // 一次批量更新而不是逐条：条目数量本就很少，但逐条 UPDATE 会让「合并一次 = N 条 SQL」，
            // 而这个动作跑在记忆合并线程池上，没必要为同样的结果多花 N-1 次往返。
            touched = mapper.update(null, new LambdaUpdateWrapper<ConversationFact>()
                    .eq(ConversationFact::getConversationId, conversationId)
                    .in(ConversationFact::getId, refreshed)
                    .set(ConversationFact::getUpdatedAt, now));
        }
        LambdaQueryWrapper<ConversationFact> stale = new LambdaQueryWrapper<ConversationFact>()
                .eq(ConversationFact::getConversationId, conversationId)
                .eq(ConversationFact::getSource, ConversationFact.SOURCE_MERGE);
        if (!desired.isEmpty()) {
            stale.notIn(ConversationFact::getFactHash, desired);
        }
        int removed = mapper.delete(stale);
        if (inserted > 0 || removed > 0) {
            log.info("长期事实条目按合并结果重写：会话={}，新增={}，刷新={}，淘汰={}",
                    conversationId, inserted, touched, removed);
        }
        return new MergeOutcome(inserted, touched, removed);
    }

    /**
     * 删除某会话的<b>自动整理</b>条目，返回条数。供「重置记忆 / 清空消息 / 切换分支版本」使用：
     * 那些动作都会作废此前的压缩产物（摘要 + 归档 + 自动条目），唯一的例外是用户手加的条目 ——
     * 它不是从历史里压缩出来的，历史变了它依然成立。
     */
    public int deleteGenerated(String conversationId) {
        if (isBlank(conversationId)) return 0;
        int n = mapper.delete(new LambdaQueryWrapper<ConversationFact>()
                .eq(ConversationFact::getConversationId, conversationId)
                .eq(ConversationFact::getSource, ConversationFact.SOURCE_MERGE));
        if (n > 0) {
            log.info("清空自动整理的事实条目：会话={}，条数={}（用户手加条目保留）", conversationId, n);
        }
        return n;
    }

    /** 删除某会话的<b>全部</b>条目（删会话时级联清理，避免留下孤儿行）。 */
    public int deleteAll(String conversationId) {
        if (isBlank(conversationId)) return 0;
        int n = mapper.delete(new LambdaQueryWrapper<ConversationFact>()
                .eq(ConversationFact::getConversationId, conversationId));
        if (n > 0) {
            log.info("级联删除会话事实条目：会话={}，条数={}", conversationId, n);
        }
        return n;
    }

    // ==================================================================
    // 解析（与 MemoryMergeService 共用的唯一口径）
    // ==================================================================

    /**
     * 解析合并产出里「关键事实」段的条目行 —— <b>唯一解析口径</b>，别再在别处写第二份。
     * <p>
     * 容忍模型的各种排版：行首的 {@code -} / {@code *} / {@code •} / {@code 1.} 前缀、markdown 粗体、
     * 缺 {@code [主题]} 前缀（按「其它」处理，宁可归错组也不要丢内容）。整段为「无」时返回空表 ——
     * 那表示「一条事实都不剩」，与解析失败必须区分（前者会清空自动条目，后者不该动库）。
     */
    public static List<FactLine> parseLines(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        List<FactLine> out = new ArrayList<>();
        for (String rawLine : raw.split("\\R")) {
            String line = stripLinePrefix(rawLine);
            if (line.isEmpty() || EMPTY_TOKENS.contains(line.toLowerCase(Locale.ROOT))) continue;
            String topic = ConversationFact.TOPIC_DEFAULT;
            String fact = line;
            if (line.charAt(0) == '[') {
                int end = line.indexOf(']');
                if (end > 1) {
                    topic = topicOf(line.substring(1, end));
                    fact = line.substring(end + 1).strip();
                }
            }
            if (fact.isEmpty()) continue;
            out.add(new FactLine(topic, fact));
        }
        return out;
    }

    /** 去掉列表符号 / 编号 / markdown 强调符，返回一行纯文本（空行归一为空串）。 */
    private static String stripLinePrefix(String raw) {
        if (raw == null) return "";
        String s = raw.strip();
        if (s.startsWith("-") || s.startsWith("*") || s.startsWith("•") || s.startsWith("·")) {
            s = s.substring(1).strip();
        } else {
            // 「1. 事实」这类有序列表：只在「前导数字 + . / 、」时才剥，避免吃掉以数字开头的事实正文
            int i = 0;
            while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
            if (i > 0 && i < s.length() && (s.charAt(i) == '.' || s.charAt(i) == '、')) {
                s = s.substring(i + 1).strip();
            }
        }
        return s.replace("**", "").strip();
    }

    // ==================================================================
    // 内部工具
    // ==================================================================

    /** 主题归一：白名单外（模型自创标签 / 用户填别的）一律归到「其它」，保证面板分组不碎成一堆一次性桶。 */
    private static String topicOf(String topic) {
        if (topic == null) return ConversationFact.TOPIC_DEFAULT;
        String t = topic.strip();
        if (t.isEmpty()) return ConversationFact.TOPIC_DEFAULT;
        return ConversationFact.TOPICS.contains(t) ? t : ConversationFact.TOPIC_DEFAULT;
    }

    /**
     * 单条事实的规范化：所有空白（含换行）折成一个空格并 strip。
     * <p>
     * <b>换行必须折掉</b>：注入文本是「一条一行」的列表格式，用户从别处粘一段多行文本进来会把它撑成
     * 好几行、破坏格式（后几行看起来像没有主题前缀的条目）。
     */
    private static String oneLine(String s) {
        if (s == null) return "";
        return s.replaceAll("\\s+", " ").strip();
    }

    /** 校验并规范化事实内容：空报 400、超长报 400（<b>不静默截断</b>——用户能改短，何必替他丢字）。 */
    private static String requireFact(String fact) {
        String text = oneLine(fact);
        if (text.isEmpty()) {
            throw new AiBusinessException(AiErrorCode.BAD_REQUEST, "事实内容不能为空");
        }
        if (text.length() > ConversationFact.FACT_MAX) {
            throw new AiBusinessException(AiErrorCode.BAD_REQUEST,
                    "单条事实最多 " + ConversationFact.FACT_MAX + " 字（当前 " + text.length() + " 字）");
        }
        return text;
    }

    private static void requireConversationId(String conversationId) {
        if (isBlank(conversationId)) {
            throw new AiBusinessException(AiErrorCode.BAD_REQUEST, "缺少会话 ID");
        }
    }

    /** 载入条目并核对其归属会话；不存在<b>或不属于该会话</b>统一 404（不区分，避免 id 变成存在性探针）。 */
    private ConversationFact require(Long id, String conversationId) {
        if (id == null) {
            throw new AiBusinessException(AiErrorCode.BAD_REQUEST, "缺少条目 ID");
        }
        ConversationFact f = mapper.selectById(id);
        if (f == null || !f.getConversationId().equals(conversationId)) {
            throw new AiBusinessException(AiErrorCode.NOT_FOUND, "事实条目不存在");
        }
        return f;
    }

    /** 按 {@code (conversation_id, fact_hash)} 唯一键取一条（至多一条）。 */
    private ConversationFact findByHash(String conversationId, String factHash) {
        if (isBlank(conversationId)) return null;
        return mapper.selectOne(new LambdaQueryWrapper<ConversationFact>()
                .eq(ConversationFact::getConversationId, conversationId)
                .eq(ConversationFact::getFactHash, factHash)
                .last("LIMIT 1"));
    }

    /**
     * 去重键：{@code topic + fact} 的 MD5（与 DDL 注释同一口径）。
     * <p>
     * 为什么不去用 fact 本身做唯一键：{@code VARCHAR(500) utf8mb4} 是 2000 字节，超过 InnoDB 的索引键长上限
     * （3072 字节下按字符数算也顶不住），故取定长摘要。至于「模型每次重新生成、措辞会微变」——
     * 那正是要靠提示词里的「仍然有效的原文照抄保留」压住的，摘要只是兜底。
     */
    private static String hash(String topic, String fact) {
        return DigestUtil.md5Hex(topic + fact);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /** 合并产出的单条事实（主题 + 内容）。 */
    public record FactLine(String topic, String fact) {
    }

    /** 一次 {@link #merge} 的结果，仅用于日志与自检。 */
    public record MergeOutcome(int inserted, int refreshed, int removed) {
    }
}
