package org.luo.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import lombok.extern.slf4j.Slf4j;
import org.luo.ai.dto.ToolApprovalDto;
import org.luo.ai.entity.ToolApproval;
import org.luo.ai.mapper.ToolApprovalMapper;
import org.luo.ai.properties.PiiProperties;
import org.luo.ai.properties.ToolApprovalProperties;
import org.luo.common.exception.AiBusinessException;
import org.luo.common.exception.AiErrorCode;
import org.luo.common.util.PiiJsonMasker;
import org.luo.common.util.PiiMasker;
import org.luo.common.util.TextClip;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 工具审批闸门服务：把「模型想调一个敏感工具」这件事变成一次需要用户点头的请求。
 * <p>
 * <b>闸门形态</b>：不阻塞、不挂起 —— 工具调用发生在 Spring AI 内部，SSE 早已建立，没有「中途暂停等人点按钮」
 * 的位置。所以走的是与 {@code HandoffTool} 同一套路：命中拦截就<b>给模型一句可读的话</b>让它收口，
 * 同时落一条待确认记录；用户批准后再用既有的「重新生成」通路重跑同一轮，那时闸门放行、工具真正执行。
 * 这条链路不新增任何发送逻辑，也不假装「按一下就能从半路续上」。
 * <p>
 * <b>状态与放行判据只有一处</b>（{@link #gate}）：{@code APPROVED} 且在有效期内才放行；{@code REJECTED}
 * 一直被挡；{@code PENDING} 与「过期/无记录」都回同一句话让用户去点。{@link ToolApprovalDto#of} 折算
 * 生效状态时用的是同一口径 —— 界面与闸门不可能各说各话。
 * <p>
 * <b>绝不向模型抛异常</b>：工具结果里冒异常会打断整轮对话，用户看到的是 500 而不是「需要确认」。闸门自身
 * 故障时也返回一段可读说明（fail-closed：宁可挡住并让用户知道，也不静默放行 —— 静默放行会让闸门变成摆设）。
 */
@Slf4j
@Service
public class ToolApprovalService {

    /** 单次列表返回上限：待确认条数天然个位数，留 50 只为兜住历史留档。 */
    private static final int MAX_LIST = 50;

    /** 入参留档上限：入参是给用户看的判据，不是执行依据，超长截断即可（避免 TEXT 列被大 SQL 灌满）。 */
    private static final int MAX_INPUT_CHARS = 4000;

    /** 用户原话留档上限（与 DDL 的 VARCHAR(1000) 对齐）。 */
    private static final int MAX_MESSAGE_CHARS = 1000;

    /** 决断备注上限（与 DDL 的 VARCHAR(255) 对齐）。 */
    private static final int NOTE_MAX = 255;

    /** 截断标记。抽成常量是因为「它有多长」直接决定 truncate 的预算计算，不能散在方法里数。 */
    private static final String TRUNCATE_SUFFIX = TextClip.MARKED;

    private final ToolApprovalMapper mapper;
    private final ToolApprovalProperties props;
    private final PiiProperties piiProperties;

    public ToolApprovalService(ToolApprovalMapper mapper, ToolApprovalProperties props,
                               PiiProperties piiProperties) {
        this.mapper = mapper;
        this.props = props;
        this.piiProperties = piiProperties;
    }

    /** 落库前脱敏；开关与消息正文共用同一个（{@code agent.pii.enabled}），不允许单独关。 */
    private String maskForStore(String text) {
        return piiProperties.enabledOn() ? PiiMasker.mask(text) : text;
    }

    /** 同上，但走结构化 JSON 脱敏（入参是 JSON，不能整串遮坏结构，见 {@link PiiJsonMasker}）。 */
    private String maskJsonForStore(String json) {
        return piiProperties.enabledOn() ? PiiJsonMasker.mask(json) : json;
    }

    // ==================== 闸门（工具回调线程调用） ====================

    /**
     * 闸门判定。
     *
     * @return {@code null} = 放行，调用方应继续执行原工具；非 null = 拦截，返回值是<b>直接回给模型的说明文本</b>
     */
    public String gate(String conversationId, Long agentId, String toolName, String inputJson, String userMessage) {
        try {
            LocalDateTime now = LocalDateTime.now();
            ToolApproval row = find(conversationId, toolName);
            if (approved(row, now)) {
                markUsed(row.getId(), now);
                log.info("工具审批闸门放行：会话={}，工具={}，已批准第 {} 次", conversationId, toolName,
                        (row.getUsedCount() == null ? 0 : row.getUsedCount()) + 1);
                return null;
            }
            if (row != null && ToolApproval.STATUS_REJECTED.equals(row.getStatus())) {
                log.info("工具审批闸门拦截（用户已拒绝）：会话={}，工具={}", conversationId, toolName);
                return rejectedText(toolName);
            }
            boolean reasked = row != null && ToolApproval.STATUS_APPROVED.equals(row.getStatus());
            upsertPending(conversationId, agentId, toolName, inputJson, userMessage, row, now);
            log.info("工具审批闸门拦截（待确认{}）：会话={}，工具={}", reasked ? "，原授权已过期" : "", conversationId, toolName);
            return pendingText(toolName, reasked);
        } catch (Exception e) {
            // fail-closed：闸门自己坏了宁可挡住并说清楚，也不静默放行（静默放行 = 闸门形同不存在）
            log.error("工具审批闸门自身故障，本次调用已拦下：会话={}，工具={}", conversationId, toolName, e);
            return "⛔ 审批闸门自身故障，工具「" + toolName + "」未被批准也未执行（详见服务日志）。"
                    + "请告知用户稍后重试，不要改用其它工具绕过。";
        }
    }

    /** 取该会话下该工具的唯一记录（唯一键保证至多一行）。 */
    private ToolApproval find(String conversationId, String toolName) {
        return mapper.selectOne(new QueryWrapper<ToolApproval>()
                .eq("conversation_id", conversationId)
                .eq("tool_name", toolName)
                .last("LIMIT 1"));
    }

    /** 放行判据：已批准 且（无有效期 或 决断时间在有效期内）。 */
    private boolean approved(ToolApproval row, LocalDateTime now) {
        if (row == null || !ToolApproval.STATUS_APPROVED.equals(row.getStatus())) return false;
        if (props.expireMinutes() <= 0) return true;
        LocalDateTime at = row.getDecidedAt() != null ? row.getDecidedAt() : row.getCreatedAt();
        return at != null && at.plusMinutes(props.expireMinutes()).isAfter(now);
    }

    /**
     * 落 / 刷新一条待确认记录。已有记录就原地改（唯一键决定同一会话同一工具只有一行），
     * 并把上一次的决断痕迹清掉 —— 否则界面会出现「状态待确认、却挂着上次批准时间」的自相矛盾。
     * <p>
     * 并发下可能撞唯一键（评审候选 / 规划步骤会并行跑）：撞了就说明别的线程刚落了这一行，
     * 本轮按「已待确认」处理即可，不需要重试。
     */
    private void upsertPending(String conversationId, Long agentId, String toolName, String inputJson,
                               String userMessage, ToolApproval row, LocalDateTime now) {
        // 两列都过脱敏：userMessage 是用户原话（用户为触发敏感工具时很可能顺口报了号码），
        // inputJson 是工具入参（参数里也可能有）。DDL 注释写的「原样留档」指的是「不加工、不改写语义」，
        // 不等于「不过脱敏」—— 这两列都直接回显在前端审批卡片上，不遮就是明文展示。
        // 幂等遮蔽 ⇒ 重复写入不累积，不会出现「遮了又遮」的二次变形。
        String input = truncate(maskJsonForStore(inputJson), MAX_INPUT_CHARS);
        String message = truncate(maskForStore(userMessage), MAX_MESSAGE_CHARS);
        if (row == null) {
            ToolApproval add = new ToolApproval();
            add.setConversationId(conversationId);
            add.setAgentId(agentId);
            add.setToolName(toolName);
            add.setInputJson(input);
            add.setUserMessage(message);
            add.setStatus(ToolApproval.STATUS_PENDING);
            add.setUsedCount(0);
            add.setCreatedAt(now);
            try {
                mapper.insert(add);
            } catch (DuplicateKeyException e) {
                log.info("并发拦截同一工具，待确认记录已由其他线程落库：会话={}，工具={}", conversationId, toolName);
            }
            return;
        }
        mapper.update(null, new UpdateWrapper<ToolApproval>()
                .eq("id", row.getId())
                .set("agent_id", agentId)
                .set("input_json", input)
                .set("user_message", message)
                .set("status", ToolApproval.STATUS_PENDING)
                .set("note", null)
                .set("decided_by", null)
                .set("decided_at", null));
    }

    /** 放行留痕：次数自增 + 记住最近一次执行时间（「批了之后到底用没用到」是闸门唯一的效果证据）。 */
    private void markUsed(Long id, LocalDateTime now) {
        try {
            mapper.update(null, new UpdateWrapper<ToolApproval>()
                    .eq("id", id)
                    .setSql("used_count = used_count + 1")
                    .set("last_used_at", now));
        } catch (Exception e) {
            // 留痕失败不影响放行：闸门的职责是「拦不拦」，不是「记不记得」
            log.warn("工具审批放行留痕失败：id={}", id, e);
        }
    }

    /** 待确认时回给模型的话：让它立刻收口并告诉用户去哪点，而不是换个办法硬上。 */
    private static String pendingText(String toolName, boolean reasked) {
        return "⛔ 安全闸门拦截：工具「" + toolName + "」需要用户确认后才能执行，本次调用已被拦下"
                + (reasked ? "（此前的一次授权已过期，需要重新确认）" : "")
                + "。系统已为用户生成一条待确认记录。请立刻停止调用工具，用一句话告知用户："
                + "执行「" + toolName + "」需要他确认，可在输入框上方的「待确认的工具调用」处批准或拒绝。"
                + "不要重试本工具，也不要改用其它工具绕过。";
    }

    /** 已拒绝时回给模型的话：说清「不是没批，是明确否了」，并给出可行的退路。 */
    private static String rejectedText(String toolName) {
        return "⛔ 工具「" + toolName + "」已被用户在本会话中拒绝执行，本次调用同样被拦下。"
                + "不要重试，也不要改用其它工具绕过；请改用不依赖该工具的方式回答，或直接说明这一部分无法完成。";
    }

    /** 超长截断（保留前段并标注）：静默截断会让用户对着一份不完整的入参做判断。 */
    /**
     * 截断到 DDL 列宽（标记 = {@link TextClip#MARKED}）。
     * <p>
     * <b>max 是列宽本身</b>，不是「加标记之后的上限」：先前写成 {@code substring(0, max) + "…（已截断）"}
     * 会产出 max+9 字符，VARCHAR(1000) 溢出后被 {@link #gate} 的 catch 吞成「闸门自身故障」并 fail-closed
     * （用户输入越长越用不了工具，而提示指向「详见服务日志」＝指错方向）；note 那条没有 try/catch，
     * 审批备注超 255 字即 500 且<b>状态没落库</b>。
     * <p>
     * 实现见 {@link TextClip}（统一收口，边界由它自己守住）。
     */
    private static String truncate(String s, int max) {
        return TextClip.clip(s, max, TRUNCATE_SUFFIX);
    }

    // ==================== 管理（HTTP 线程调用） ====================

    /** 本人某会话下的审批记录（时间倒序）。不存在或非本人读不到（列表天然为空）。 */
    public List<ToolApprovalDto> list(Long userId, String conversationId) {
        return mapper.selectOwned(userId, conversationId, MAX_LIST).stream()
                .map(e -> ToolApprovalDto.of(e, props.expireMinutes()))
                .toList();
    }

    /** 按 id 取本人可管的记录；不存在与非本人一律返回 null（调用方统一 404）。 */
    public ToolApprovalDto get(Long id, Long userId) {
        return ToolApprovalDto.of(mapper.selectOwnedById(id, userId), props.expireMinutes());
    }

    /** 批准：本会话内该工具放行（有效期内不再逐次打断）。返回更新后的投影；非本人 / 不存在返回 null。 */
    public ToolApprovalDto approve(Long id, Long userId, String note) {
        return decide(id, userId, ToolApproval.STATUS_APPROVED, note);
    }

    /** 拒绝：本会话内该工具一直挡住（不反复弹窗）。返回更新后的投影；非本人 / 不存在返回 null。 */
    public ToolApprovalDto reject(Long id, Long userId, String note) {
        return decide(id, userId, ToolApproval.STATUS_REJECTED, note);
    }

    /**
     * 撤销决断：回到待确认，让用户改主意。
     * <p>
     * 存在的理由：拒绝是「一票到底」，而人总会点错或改主意 —— 没有撤销口，用户只能删会话。
     * 批准则不必撤销（它本来就会过期，且过期后自动回到待确认）。
     */
    public ToolApprovalDto reset(Long id, Long userId) {
        ToolApproval owned = mapper.selectOwnedById(id, userId);
        if (owned == null) return null;
        mapper.update(null, new UpdateWrapper<ToolApproval>()
                .eq("id", id)
                .set("status", ToolApproval.STATUS_PENDING)
                .set("note", null)
                .set("decided_by", null)
                .set("decided_at", null));
        return get(id, userId);
    }

    /**
     * 决断共同路径：先判归属（SELECT 带 JOIN），再按 id <b>带前置态</b>更新，最后回读投影。
     * <p>
     * <b>WHERE 必须带 {@code status = PENDING}</b>：只按 id 更新时，先点「拒绝」再点「批准」
     * （或前端重复点击、网络重试）后到的 update 会无条件覆盖 status，而 {@code decided_by/decided_at}
     * 一并被覆盖、无从追溯。更糟的交错是它与 {@link #gate} 重跑同时发生 ——
     * gate 把 REJECTED 打回 PENDING 后，同一工具可能真的被执行，而用户明明已经拒绝过。
     * 带上前置态后 affected=0 即表示「状态已被别人改过」，按冲突返回，不静默覆盖。
     *
     * @return 更新后的投影；非本人 / 不存在返回 null；<b>状态已被改动</b>抛 CONFLICT
     */
    private ToolApprovalDto decide(Long id, Long userId, String status, String note) {
        ToolApproval owned = mapper.selectOwnedById(id, userId);
        if (owned == null) return null;
        int affected = mapper.update(null, new UpdateWrapper<ToolApproval>()
                .eq("id", id)
                .eq("status", ToolApproval.STATUS_PENDING)
                .set("status", status)
                .set("note", truncate(maskForStore(note), NOTE_MAX))
                .set("decided_by", userId)
                .set("decided_at", LocalDateTime.now()));
        if (affected == 0) {
            // 已被另一处改动（重跑打回 PENDING、重复提交、并发双击）：不覆盖，把当前状态如实报回去
            ToolApprovalDto current = get(id, userId);
            throw new AiBusinessException(AiErrorCode.CONFLICT,
                    "该审批的状态已变更（当前：" + describeStatus(current) + "），本次操作未生效，请刷新后重试");
        }
        log.info("工具审批决断：id={}，工具={}，结论={}，操作人={}", id, owned.getToolName(), status, userId);
        return get(id, userId);
    }

    /** 把状态码说成人话，让「状态已变更」这条报错能被用户直接看懂。 */
    private static String describeStatus(ToolApprovalDto dto) {
        if (dto == null) return "未知";
        return switch (dto.effective() == null ? "" : dto.effective()) {
            case ToolApproval.STATUS_PENDING -> "待确认";
            case ToolApproval.STATUS_APPROVED -> "已批准";
            case ToolApproval.STATUS_REJECTED -> "已拒绝";
            default -> dto.effective();
        };
    }
}
