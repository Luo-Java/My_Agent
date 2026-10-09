package org.luo.ai.service;

import cn.hutool.crypto.digest.DigestUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.extern.slf4j.Slf4j;
import org.luo.ai.dto.ConversationFactDto;
import org.luo.ai.entity.ConversationFact;
import org.luo.ai.mapper.ConversationFactMapper;
import org.luo.ai.properties.PiiProperties;
import org.luo.common.exception.AiBusinessException;
import org.luo.common.exception.AiErrorCode;
import org.luo.common.util.PiiMasker;
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

    /** 每个会话最多留档多少条「已被取代」的旧说法（更早的清理掉，避免死数据无限堆积）。 */
    private static final int KEEP_SUPERSEDED = 20;

    private final ConversationFactMapper mapper;
    private final PiiProperties piiProperties;

    public ConversationFactService(ConversationFactMapper mapper, PiiProperties piiProperties) {
        this.mapper = mapper;
        this.piiProperties = piiProperties;
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

    /** 同上，转页面视图。批量渲染共用同一个「当前时刻」，避免同一列表里两条条目得出不同的过期结论。 */
    public List<ConversationFactDto> listDto(String conversationId) {
        LocalDateTime now = LocalDateTime.now();
        return list(conversationId).stream().map(f -> ConversationFactDto.of(f, now)).toList();
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
        LocalDateTime now = LocalDateTime.now();
        // 只注入「生效中且未过期」的条目：被新说法替代的（SUPERSEDED）与已过期的都不该再进上下文 ——
        // 这正是本次改造要解决的问题（此前它们一律照常注入，模型于是把过期待办当成永远的待办）。
        List<ConversationFact> injectable = list(conversationId).stream()
                .filter(ConversationFactService::isActive)
                .filter(f -> f.getExpiresAt() == null || f.getExpiresAt().isAfter(now))
                .toList();
        return renderLines(injectable);
    }

    /** 是否「生效中」（存量行的 status 可能为 NULL，按生效处理 —— 不因加了列就让老数据凭空消失）。 */
    private static boolean isActive(ConversationFact f) {
        return f.getStatus() == null || ConversationFact.STATUS_ACTIVE.equals(f.getStatus());
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
        return add(conversationId, topic, fact, null);
    }

    /**
     * 手动新增一条事实（可带有效期）。
     *
     * @param expiresAt 有效期（可空 = 永不过期）；已写下的「下周三要交报告」这类事实靠它自动退场
     */
    public ConversationFact add(String conversationId, String topic, String fact, LocalDateTime expiresAt) {
        requireConversationId(conversationId);
        String t = topicOf(topic);
        String text = requireFact(fact);
        ConversationFact exist = findByHash(conversationId, hash(t, text));
        if (exist != null) {
            if (ConversationFact.SOURCE_USER.equals(exist.getSource()) && isActive(exist)) {
                throw new AiBusinessException(AiErrorCode.BAD_REQUEST, "该事实已存在（同主题同内容）");
            }
            // 认领：把模型整理的条目转成手动来源（此后合并不再淘汰它）；被替代/过期的条目则重新激活 ——
            // 用户又写了一遍，就是明确表示「这条现在有效」，此时把 expires_at 一并按本次请求覆盖。
            mapper.update(null, new LambdaUpdateWrapper<ConversationFact>()
                    .eq(ConversationFact::getId, exist.getId())
                    .set(ConversationFact::getSource, ConversationFact.SOURCE_USER)
                    .set(ConversationFact::getConfidence, ConversationFact.CONFIDENCE_USER)
                    .set(ConversationFact::getStatus, ConversationFact.STATUS_ACTIVE)
                    .set(ConversationFact::getSupersededBy, null)
                    .set(ConversationFact::getExpiresAt, expiresAt)
                    .set(ConversationFact::getUpdatedAt, LocalDateTime.now()));
            log.info("手动添加的事实与既有条目重合，已转为手动来源并重新激活：会话={}，条目={}",
                    conversationId, exist.getId());
            exist.setSource(ConversationFact.SOURCE_USER);
            exist.setConfidence(ConversationFact.CONFIDENCE_USER);
            exist.setStatus(ConversationFact.STATUS_ACTIVE);
            exist.setExpiresAt(expiresAt);
            return exist;
        }
        LocalDateTime now = LocalDateTime.now();
        ConversationFact f = new ConversationFact();
        f.setConversationId(conversationId);
        f.setTopic(t);
        f.setFact(text);
        f.setFactHash(hash(t, text));
        f.setSource(ConversationFact.SOURCE_USER);
        f.setConfidence(ConversationFact.CONFIDENCE_USER);
        f.setExpiresAt(expiresAt);
        f.setStatus(ConversationFact.STATUS_ACTIVE);
        f.setCreatedAt(now);
        f.setUpdatedAt(now);
        mapper.insert(f);
        log.info("手动新增长期事实：会话={}，主题={}，长度={}，有效期={}", conversationId, t, text.length(),
                expiresAt == null ? "永久" : expiresAt);
        return f;
    }

    /** 修改一条事实（主题与内容都可改），有效期保持不变。 */
    public ConversationFact update(Long id, String conversationId, String topic, String fact) {
        return update(id, conversationId, topic, fact, null);
    }

    /**
     * 修改一条事实（主题与内容都可改，可同时改有效期）。
     * <p>
     * <b>改过的条目一律转为 {@code USER} 来源</b>：用户为什么要改它？因为模型记错了。那么这条就不该再
     * 由模型下一次整理去覆盖或删除 —— 来源从「自动」变「手动」正是这个意思。置信度同步提到用户档（5）：
     * 人手写下的东西不需要靠「被反复确认」来挣信用。
     * <p>
     * <b>顺带复活</b>：如果这条此前已被新说法取代（SUPERSEDED）或已过期，人工改完即视为重新生效 ——
     * 用户专门去改一条死条目，只可能是「它现在又对了」。同理，若改后的内容与另一条<b>已失效</b>的条目撞车，
     * 不是报错而是把那条标成「被本条取代」（复用合并侧的冲突策略），避免留下两条同 hash 的行让后续查询二义。
     *
     * @param expiresAt 有效期；<b>传 null 表示清除有效期</b>（重新变成永久有效），不是「保持原值」
     */
    public ConversationFact update(Long id, String conversationId, String topic, String fact, LocalDateTime expiresAt) {
        ConversationFact exist = require(id, conversationId);
        String t = topicOf(topic);
        String text = requireFact(fact);
        String h = hash(t, text);
        ConversationFact other = findByHash(conversationId, h);
        if (other != null && !other.getId().equals(exist.getId())) {
            if (isActive(other)) {
                throw new AiBusinessException(AiErrorCode.BAD_REQUEST, "已存在相同的事实条目（同主题同内容）");
            }
            // 撞上一条已失效的旧行：把它标成「被本条取代」，与合并侧的冲突策略同一口径。
            mapper.update(null, new LambdaUpdateWrapper<ConversationFact>()
                    .eq(ConversationFact::getId, other.getId())
                    .set(ConversationFact::getStatus, ConversationFact.STATUS_SUPERSEDED)
                    .set(ConversationFact::getSupersededBy, exist.getId()));
        }
        LocalDateTime now = LocalDateTime.now();
        boolean promoted = !ConversationFact.SOURCE_USER.equals(exist.getSource());
        mapper.update(null, new LambdaUpdateWrapper<ConversationFact>()
                .eq(ConversationFact::getId, exist.getId())
                .set(ConversationFact::getTopic, t)
                .set(ConversationFact::getFact, text)
                .set(ConversationFact::getFactHash, h)
                .set(ConversationFact::getSource, ConversationFact.SOURCE_USER)
                .set(ConversationFact::getConfidence, ConversationFact.CONFIDENCE_USER)
                .set(ConversationFact::getStatus, ConversationFact.STATUS_ACTIVE)
                .set(ConversationFact::getSupersededBy, null)
                .set(ConversationFact::getExpiresAt, expiresAt)
                .set(ConversationFact::getUpdatedAt, now));
        if (promoted) {
            log.info("自动整理条目被人工修正，已转为手动来源：会话={}，条目={}", conversationId, exist.getId());
        }
        log.info("修改长期事实：会话={}，条目={}，主题={}，长度={}，有效期={}", conversationId, exist.getId(), t,
                text.length(), expiresAt == null ? "永久" : expiresAt);
        exist.setTopic(t);
        exist.setFact(text);
        exist.setFactHash(h);
        exist.setSource(ConversationFact.SOURCE_USER);
        exist.setConfidence(ConversationFact.CONFIDENCE_USER);
        exist.setStatus(ConversationFact.STATUS_ACTIVE);
        exist.setSupersededBy(null);
        exist.setExpiresAt(expiresAt);
        exist.setUpdatedAt(now);
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
        List<ConversationFact> existing = list(conversationId);
        Map<String, ConversationFact> byHash = new HashMap<>();
        for (ConversationFact f : existing) {
            byHash.put(f.getFactHash(), f);
        }
        LocalDateTime now = LocalDateTime.now();
        Set<String> desired = new LinkedHashSet<>();
        List<Long> refreshed = new ArrayList<>();
        // 本轮该主题「换了新说法」的记录：主题 → 新条目 id。用于把同主题下没被复述的旧说法标成「已被取代」。
        Map<String, Long> replacedByTopic = new LinkedHashMap<>();
        int inserted = 0;
        for (FactLine line : (wanted == null ? List.<FactLine>of() : wanted)) {
            // 与 requireFact 同一个口径：合并产出的事实也是从用户消息里复述出来的，同样要遮。
            // hash 用的是脱敏后的 text ⇒ 「同内容同 hash」的重语义不因脱敏而破裂。
            String text = maskForStore(oneLine(line.fact()));
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
                f.setConfidence(ConversationFact.CONFIDENCE_MERGE);
                f.setStatus(ConversationFact.STATUS_ACTIVE);
                f.setCreatedAt(now);
                f.setUpdatedAt(now);
                mapper.insert(f);
                inserted++;
                replacedByTopic.put(t, f.getId());
            } else {
                refreshed.add(hit.getId());
            }
        }
        // 1) 刷新：本轮仍被列出的条目 → 刷新时间、置信度 +1（封顶 5），并把「已被取代」的重新激活 ——
        //    模型这次又列出它，说明那个新说法没站住，旧的回来了。置信度用 SQL 自增而非「读出再写回」，
        //    免得合并线程与用户手改并发时互相覆盖。LEAST/COALESCE 兜住存量行 confidence 为 NULL 的情况。
        int touched = 0;
        if (!refreshed.isEmpty()) {
            // 一次批量更新而不是逐条：条目数量本就很少，但逐条 UPDATE 会让「合并一次 = N 条 SQL」，
            // 而这个动作跑在记忆合并线程池上，没必要为同样的结果多花 N-1 次往返。
            touched = mapper.update(null, new LambdaUpdateWrapper<ConversationFact>()
                    .eq(ConversationFact::getConversationId, conversationId)
                    .in(ConversationFact::getId, refreshed)
                    .set(ConversationFact::getUpdatedAt, now)
                    .setSql("confidence = LEAST(" + ConversationFact.CONFIDENCE_MAX
                            + ", COALESCE(confidence, " + ConversationFact.CONFIDENCE_MERGE + ") + 1)")
                    .set(ConversationFact::getStatus, ConversationFact.STATUS_ACTIVE)
                    .set(ConversationFact::getSupersededBy, null));
        }
        // 2) 冲突消解：某个主题本轮出现了新说法，而该主题下还有没被复述的旧说法 → 标「已被取代」留档。
        //    这里刻意<b>不删除</b>：旧说法与新说法是同一件事的两个时间点，「它曾经是什么」本身有价值
        //    （用户会对着面板问「我上周不是改过吗」）。不注入即可，见 linesText 的过滤。
        int superseded = replacedByTopic.isEmpty() ? 0 : markSuperseded(existing, desired, replacedByTopic, now);
        // 3) 淘汰：模型这次完全没提、且所属主题也没有新说法的自动条目 → 删除。
        //    这才是「淘汰旧事实的唯一通路」，与上一轮的语义完全一致；用户手加条目永不在候选内。
        List<Long> staleIds = new ArrayList<>();
        for (ConversationFact f : existing) {
            if (!ConversationFact.SOURCE_MERGE.equals(f.getSource())) continue;
            if (desired.contains(f.getFactHash())) continue;
            if (replacedByTopic.containsKey(f.getTopic())) continue;   // 已标「被取代」，留档不删
            staleIds.add(f.getId());
        }
        int removed = staleIds.isEmpty() ? 0 : mapper.delete(new LambdaQueryWrapper<ConversationFact>()
                .in(ConversationFact::getId, staleIds));
        if (superseded > 0) pruneSuperseded(conversationId);
        if (inserted > 0 || removed > 0 || superseded > 0) {
            log.info("长期事实条目按合并结果重写：会话={}，新增={}，刷新={}，取代={}，淘汰={}",
                    conversationId, inserted, touched, superseded, removed);
        }
        return new MergeOutcome(inserted, touched, removed);
    }

    /**
     * 把「同主题下已被新说法顶替」的旧条目标为 {@link ConversationFact#STATUS_SUPERSEDED}，返回条数。
     * <p>
     * 判据是<b>主题相同 + 本轮没被复述 + 该主题本轮产出了新条目</b>三件事同时成立。只对 {@code MERGE} 来源生效 ——
     * 用户手加的条目即使与模型的新说法同主题也不动它（那是两码事，用户写的可能一直在用）。
     */
    private int markSuperseded(List<ConversationFact> existing, Set<String> desired,
                               Map<String, Long> replacedByTopic, LocalDateTime now) {
        int n = 0;
        for (ConversationFact f : existing) {
            if (!ConversationFact.SOURCE_MERGE.equals(f.getSource())) continue;
            if (!isActive(f)) continue;
            if (desired.contains(f.getFactHash())) continue;
            Long newId = replacedByTopic.get(f.getTopic());
            if (newId == null || newId.equals(f.getId())) continue;
            mapper.update(null, new LambdaUpdateWrapper<ConversationFact>()
                    .eq(ConversationFact::getId, f.getId())
                    .set(ConversationFact::getStatus, ConversationFact.STATUS_SUPERSEDED)
                    .set(ConversationFact::getSupersededBy, newId)
                    .set(ConversationFact::getUpdatedAt, now));
            n++;
        }
        return n;
    }

    /**
     * 给「已被取代」的留档行收口：只保留最近 {@link #KEEP_SUPERSEDED} 条，更早的删掉。
     * <p>
     * 留档不是无限留 —— 同一主题改来改去，一次改一行会攒成几百行死数据。代价是「很久以前的旧说法查不到」，
     * 这个取舍是明确的：面板要回答的是「我最近一次改了什么」，不是「三年前是什么」。
     */
    private int pruneSuperseded(String conversationId) {
        List<ConversationFact> sup = mapper.selectList(new LambdaQueryWrapper<ConversationFact>()
                .eq(ConversationFact::getConversationId, conversationId)
                .eq(ConversationFact::getStatus, ConversationFact.STATUS_SUPERSEDED)
                .orderByDesc(ConversationFact::getId));
        if (sup.size() <= KEEP_SUPERSEDED) return 0;
        List<Long> drop = sup.subList(KEEP_SUPERSEDED, sup.size()).stream().map(ConversationFact::getId).toList();
        return mapper.delete(new LambdaQueryWrapper<ConversationFact>().in(ConversationFact::getId, drop));
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
    /** 落库前脱敏；开关与消息正文共用同一个（{@code agent.pii.enabled}），不允许单独关。 */
    private String maskForStore(String text) {
        return piiProperties.enabledOn() ? PiiMasker.mask(text) : text;
    }

    private static String oneLine(String s) {
        if (s == null) return "";
        return s.replaceAll("\\s+", " ").strip();
    }

    /**
     * 校验并规范化事实内容：空报 400、超长报 400（<b>不静默截断</b>——用户能改短，何必替他丢字）。
     * <p>
     * <b>顺带脱敏</b>：用户在长期记忆面板手写的内容与消息正文是同一份信任级别（都是「用户输入」），
     * 而 {@code SOURCE_USER} 条目<b>合并不删不改</b>、事实上永久留存，还在每轮被注入上下文 ——
     * 不遮就等于用户主动把号码写进了永久记忆。故脱敏在「规范化」这一步做，{@code add}/{@code update} 两条路径共用。
     */
    private String requireFact(String fact) {
        String text = maskForStore(oneLine(fact));
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
