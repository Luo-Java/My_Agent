package org.luo.ai.dto;

/**
 * 会话导出结果（Markdown 文本 + 建议文件名）。
 * <p>
 * 刻意不落盘、不走 {@code /files/**}：下载必须带 {@code Authorization}，浏览器对裸链接的导航请求带不上
 * 这个头，走文件通道只会 401。故后端只回文本，由前端拼 Blob 触发下载。
 *
 * @param filename 建议文件名（已按标题生成并做过非法字符归一，前端直接用作 download 属性）
 * @param content  Markdown 全文
 */
public record ConversationExport(String filename, String content) {
}
