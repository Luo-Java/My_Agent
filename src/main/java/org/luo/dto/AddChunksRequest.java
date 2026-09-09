package org.luo.dto;

import java.util.List;

/**
 * 向知识库批量添加知识请求。
 *
 * @param source 来源标注（可选，如「产品手册」「校园制度.md」），每个知识块都会带上该来源，
 *               检索命中后供模型引用溯源、前端列表展示
 * @param texts  知识文本列表（可多条，也可一条长文本——服务端会自动按段落分块）
 */
public record AddChunksRequest(String source, List<String> texts) {
}
