package org.luo.ai.dto;

/**
 * 单条知识块详情（引用回链「查看原文」的返回体）。
 * <p>
 * 与 {@code KbChunkPageResult.list} 里的 {@link org.luo.ai.entity.KnowledgeChunk} 的唯一区别是
 * <b>不含 {@code embedding}</b>：向量是 1024 维 JSON 串（数十 KB），展示原文完全用不到，
 * 顺手带出去等于每次点「原文」都多传几十 KB。其余字段与展示所需一致。
 * <p>
 * {@code kbName} 是<b>查询时</b>从 {@code kb} 表取的名字，不是知识块自带的（块只存 {@code kb_id}）——
 * 库被删而块残留时它会为 null，前端按「未知知识库」兜底显示，而不是让接口整体失败。
 *
 * @param chunkId 知识块 ID（kb_chunk.id）
 * @param kbId    所属知识库 ID
 * @param kbName  知识库名称（库已删时为 null）
 * @param source  来源标注（通常是文件名；可能为空）
 * @param content 知识块原文
 */
public record KbChunkDetail(Long chunkId, Long kbId, String kbName, String source, String content) {
}
