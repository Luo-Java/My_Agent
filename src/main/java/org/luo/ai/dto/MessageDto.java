package org.luo.ai.dto;

import java.util.List;

/**
 * 历史消息项。
 *
 * @param id             消息主键。写回类操作（「不参与记忆」开关）靠它定位；只暴露主键无额外风险 —— 对应接口先按会话归属校验，拿别人的 id 一律 404
 * @param role           user / assistant
 * @param content        消息内容
 * @param attachments    本轮附件展示元数据（仅 user 可能有；无附件为空列表）。仅用于历史渲染，不参与 LLM 上下文
 * @param citations      本轮 RAG 引用来源（仅 assistant 可能有；无引用为空列表）。仅用于历史渲染 [n] 角标，不参与 LLM 上下文
 * @param turn           该消息所属的对话分支版本；{@code null}=这一轮从未分叉。同一轮所有消息拿到的是<b>同一份</b> turn，切换器只在提问上渲染
 * @param memoryExcluded 是否被标记「不参与记忆」：true 则不进上下文、不进滚动摘要，但<b>历史里照常显示</b> —— 前端据此渲染开关选中态
 */
public record MessageDto(Long id, String role, String content, List<AttachmentDto> attachments,
                         List<KbCitation> citations, Turn turn, boolean memoryExcluded) {

    /**
     * 对话分支版本。
     *
     * @param groupId      分支组 ID（切版本时回传）
     * @param version      本条消息所属的版本号（从 1 开始）
     * @param versionCount 该组的总版本数（含未生效的）；{@code <=1} 时前端不渲染切换器
     */
    public record Turn(String groupId, int version, int versionCount) {

        /** 是否值得渲染切换器：只有一个版本时切了也没变化。 */
        public boolean switchable() {
            return versionCount > 1;
        }
    }

    /** 无主键、无附件、无引用、无分支、参与记忆的便捷构造（旧数据 / 无需展示溯源的消息）。 */
    public MessageDto(String role, String content) {
        this(null, role, content, List.of(), List.of(), null, false);
    }
}
