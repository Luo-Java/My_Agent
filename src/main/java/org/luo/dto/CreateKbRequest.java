package org.luo.dto;

/**
 * 创建 / 重命名知识库请求。
 *
 * @param name          知识库名称（必填）
 * @param description   知识库说明（可选）
 * @param agentId       归属智能体 ID：非空 = 为该智能体创建专属知识库（每个智能体至多一个）；
 *                      null = 全局知识库（可在任意会话的资料库选择器中选用）。创建全局库时前端一般不传（由服务端 getOrCreateGlobal 保证单例）
 * @param chunkStrategy 默认分片策略 key（fixed/paragraph/recursive/markdown）；null/未知回退 recursive。
 *                      创建时不传落默认；设置时 null = 不修改当前值
 * @param chunkOverlap  默认相邻块重叠字符数（0 = 不重叠）；null 创建时落默认 60、
 *                      设置时表示不修改当前值（注意 0 是合法值，会正常保存）
 */
public record CreateKbRequest(String name, String description, Long agentId,
                              String chunkStrategy, Integer chunkOverlap) {
}
