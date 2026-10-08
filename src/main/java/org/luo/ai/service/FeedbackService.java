package org.luo.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.luo.ai.dto.FeedbackRequest;
import org.luo.ai.dto.MessageFeedbackDto;
import org.luo.ai.entity.ChatMessage;
import org.luo.ai.entity.EvalCaseEntity;
import org.luo.ai.entity.MessageFeedback;
import org.luo.ai.mapper.ChatMessageMapper;
import org.luo.ai.mapper.MessageFeedbackMapper;
import org.luo.ai.trace.SelfEvalService;
import org.luo.common.exception.AiBusinessException;
import org.luo.common.exception.AiErrorCode;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 消息反馈服务：用户对某条助手回复的显式评价（👍 / 👎），以及把它转成回归用例的入口。
 * <p>
 * <b>反馈的价值不在「记录」，而在「可复用」</b>：一条躺在表里的点踩只是留档，转成断言之后它才会在下次改
 * 提示词时替用户把问题再问一遍。所以本服务刻意和评测域连成一条链路（见 {@link #promote}）。
 * <p>
 * <b>一人对一条消息一票</b>：改主意是改票（原地覆盖），不是追加历史 —— 否则「先踩后赞」会在库里留下两条
 * 互相矛盾的记录，转用例时不知道该信哪条。点赞时清空问题分类，避免留下「点赞 + 编造」这种自相矛盾的组合。
 * <p>
 * <b>点踩还有第二个用途</b>：强制对那一轮做一次回答自评（见 {@link #triggerSelfEval}）。用户的判断与模型的
 * 自评分对着看才有价值 —— 都对上说明问题确实可自检，对不上说明自评本身有盲区。这是自评开关默认关闭、
 * 却<b>不看开关</b>的唯一入口。
 */
@Slf4j
@Service
public class FeedbackService {

    /** 补充说明长度上限（与 {@code message_feedback.comment} 的列宽一致）。超限明确报错，不静默截断。 */
    private static final int COMMENT_MAX = 500;

    /** 助手消息的 role 值（项目里以字面量使用，见 {@code ConversationService}）。 */
    private static final String ROLE_ASSISTANT = "assistant";

    private final MessageFeedbackMapper feedbackMapper;
    private final ChatMessageMapper messageMapper;
    private final ConversationService conversationService;
    private final EvalCaseService evalCaseService;
    /** 线上回答自评：点踩时对那一轮强制自评（不看开关与采样率，见其类注释）。 */
    private final SelfEvalService selfEvalService;

    public FeedbackService(MessageFeedbackMapper feedbackMapper, ChatMessageMapper messageMapper,
                           ConversationService conversationService, EvalCaseService evalCaseService,
                           SelfEvalService selfEvalService) {
        this.feedbackMapper = feedbackMapper;
        this.messageMapper = messageMapper;
        this.conversationService = conversationService;
        this.evalCaseService = evalCaseService;
        this.selfEvalService = selfEvalService;
    }

    /**
     * 提交或更新一条反馈。
     * <p>
     * 归属按<b>消息所属会话</b>校验（不是按消息本身）：他人消息与不存在的消息统一 404，避免
     * {@code messageId} 变成存在性探针（与「会话按用户隔离」同一原则）。
     * <p>
     * 首次提交时快照那一轮的用户输入（{@code selectPrevUserInput}）—— 转用例时要拿它当 {@code input}；
     * 改票时<b>不动</b>这个快照（它记录的是历史事实，不随评价变化）。
     */
    public MessageFeedbackDto submit(Long messageId, Long userId, FeedbackRequest req) {
        if (req == null || req.rating() == null || req.rating().isBlank()) {
            throw new AiBusinessException(AiErrorCode.BAD_REQUEST, "缺少 rating（UP=有用 / DOWN=有问题）");
        }
        String rating = req.rating().trim().toUpperCase(Locale.ROOT);
        if (!MessageFeedback.RATING_UP.equals(rating) && !MessageFeedback.RATING_DOWN.equals(rating)) {
            throw new AiBusinessException(AiErrorCode.BAD_REQUEST, "rating 只支持 UP / DOWN");
        }
        String rawReason = req.reason() == null ? null : req.reason().trim().toUpperCase(Locale.ROOT);
        if (rawReason != null && !rawReason.isEmpty()
                && Arrays.stream(MessageFeedback.REASONS).noneMatch(r -> r.equals(rawReason))) {
            throw new AiBusinessException(AiErrorCode.BAD_REQUEST,
                    "reason 只支持 " + String.join(" / ", MessageFeedback.REASONS));
        }
        // 点赞不保留问题分类（见类注释：不留「点赞 + 编造」这种自相矛盾的组合）
        String reason = MessageFeedback.RATING_UP.equals(rating) ? null : rawReason;
        String comment = req.comment() == null ? null : req.comment().trim();
        if (comment != null && comment.isEmpty()) comment = null;
        if (comment != null && comment.length() > COMMENT_MAX) {
            throw new AiBusinessException(AiErrorCode.BAD_REQUEST,
                    "补充说明最多 " + COMMENT_MAX + " 字（当前 " + comment.length() + " 字）");
        }

        ChatMessage msg = messageMapper.selectById(messageId);
        if (msg == null) {
            throw new AiBusinessException(AiErrorCode.NOT_FOUND, "消息不存在");
        }
        conversationService.checkAccess(msg.getConversationId(), userId);
        if (!ROLE_ASSISTANT.equals(msg.getRole())) {
            throw new AiBusinessException(AiErrorCode.BAD_REQUEST, "只能对助手的回复做反馈");
        }

        LocalDateTime now = LocalDateTime.now();
        MessageFeedback exist = find(messageId, userId);
        MessageFeedback saved;
        if (exist == null) {
            MessageFeedback f = new MessageFeedback();
            f.setMessageId(messageId);
            f.setConversationId(msg.getConversationId());
            f.setUserId(userId);
            f.setRating(rating);
            f.setReason(reason);
            f.setComment(comment);
            f.setUserInput(messageMapper.selectPrevUserInput(msg.getConversationId(), messageId));
            f.setCreatedAt(now);
            f.setUpdatedAt(now);
            feedbackMapper.insert(f);
            log.info("新增消息反馈：消息={}，评价={}，分类={}", messageId, rating, reason);
            saved = f;
        } else {
            exist.setRating(rating);
            exist.setReason(reason);
            exist.setComment(comment);
            exist.setUpdatedAt(now);
            feedbackMapper.updateById(exist);
            log.info("更新消息反馈：消息={}，评价={}，分类={}", messageId, rating, reason);
            saved = exist;
        }
        // 点踩 = 用户明确说「这条有问题」⇒ 强制对该轮自评（不看 enabled / 采样率），结果补写进追踪行。
        // 顺序刻意放在落库之后：先保证用户表达被持久化，再去做那件「附加」的事。
        if (MessageFeedback.RATING_DOWN.equals(rating)) {
            triggerSelfEval(msg, saved);
        }
        return MessageFeedbackDto.of(saved);
    }

    /**
     * 触发点踩强制自评（best-effort）。
     * <p>
     * <b>整段 try/catch</b>：自评是「可能缺席的附加数据」—— 追踪行可能因队列满被丢弃、那一轮的输入可能超长
     * 导致匹配不上、自评本身也可能调用失败。任何一种都不该让反馈提交失败：用户点的是「这条回答有问题」，
     * 这个表达必须落库。匹配不到时 {@link SelfEvalService} 内部会记 WARN，不静默。
     * <p>
     * <b>重复点踩会重复自评</b>（每次是一次真实模型调用）。不做「这一轮已评过就跳过」的去重，是因为那要读
     * {@code self_eval_json} 判 trigger，而点踩本就是低频显式动作；更要紧的是，一旦按「已评分」去重，
     * 「先被采样评过一次、用户后来才点踩」这条最有价值的对照信号就会被吞掉。
     */
    private void triggerSelfEval(ChatMessage msg, MessageFeedback fb) {
        try {
            selfEvalService.evaluateByFeedback(msg.getConversationId(), fb.getUserInput(), msg.getContent());
        } catch (Exception e) {
            log.warn("点踩自评触发失败（反馈本身已提交）：消息={}，原因={}", msg.getId(), e.getMessage());
        }
    }

    /** 某会话下的全部反馈（按提交顺序），供前端在消息上回显「已反馈」状态。 */
    public List<MessageFeedbackDto> listByConversation(String conversationId, Long userId) {
        conversationService.checkAccess(conversationId, userId);
        return feedbackMapper.selectList(new LambdaQueryWrapper<MessageFeedback>()
                        .eq(MessageFeedback::getConversationId, conversationId)
                        .orderByAsc(MessageFeedback::getId))
                .stream().map(MessageFeedbackDto::of).toList();
    }

    /**
     * 把一条反馈转成库内回归用例（前端「保存为回归用例」按钮）。
     * <p>
     * 预填什么、为什么只能预填这些，见 {@link EvalCaseService#createFromFeedback}。
     */
    public EvalCaseEntity promote(Long feedbackId) {
        return evalCaseService.createFromFeedback(feedbackId);
    }

    /** 按「消息 + 用户」定位已有反馈（唯一键，至多一条）。 */
    private MessageFeedback find(Long messageId, Long userId) {
        return feedbackMapper.selectOne(new LambdaQueryWrapper<MessageFeedback>()
                .eq(MessageFeedback::getMessageId, messageId)
                .eq(MessageFeedback::getUserId, userId)
                .last("LIMIT 1"));
    }
}
