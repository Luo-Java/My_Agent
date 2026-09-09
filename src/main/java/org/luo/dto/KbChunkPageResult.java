package org.luo.dto;

import org.luo.entity.KnowledgeChunk;

import java.util.List;

/**
 * 知识库知识块分页结果。
 *
 * @param total 总块数
 * @param list  当前页知识块（content 可能较长，前端展示时截断）
 */
public record KbChunkPageResult(long total, List<KnowledgeChunk> list) {
}
