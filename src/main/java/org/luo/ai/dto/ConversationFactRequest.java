package org.luo.ai.dto;

import java.time.LocalDateTime;

/**
 * 新增 / 修改长期事实条目的请求体（POST 与 PUT 共用一份：两者要填的东西完全相同）。
 * <p>
 * 只有 {@code topic} / {@code fact} / {@code expiresAt} 三个字段：{@code source} 由服务端按「谁写的」决定
 * （用户手写的一律记为 USER，模型整理出来的记为 MERGE），不接受客户端指定 —— 否则前端可以把自动条目
 * 伪装成手动条目，让它永远不会被合并淘汰。{@code factHash} / {@code confidence} / {@code status} 同理
 * 由服务端维护，客户端传了也不采信。
 *
 * @param topic     主题标签；null / 空白 / 白名单外的值一律归到「其它」
 * @param fact      事实内容；空白会被拒（400），超长同样报错而不是静默截断
 * @param expiresAt 有效期（可空 = 永不过期）。修改时传 null 表示<b>清除有效期</b>（重新变成永久有效），
 *                  而不是「保持原值」—— 表单上「有效期」留空就是「不过期」，这与用户的直观预期一致
 */
public record ConversationFactRequest(String topic, String fact, LocalDateTime expiresAt) {

    /** 兼容旧调用点（不带有效期）。 */
    public ConversationFactRequest(String topic, String fact) {
        this(topic, fact, null);
    }
}
