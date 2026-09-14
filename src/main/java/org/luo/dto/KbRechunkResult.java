package org.luo.dto;

/**
 * 重新分片（rechunk）的结果：文件在该次调用后**实际生效**的分块配置与块数。
 * <p>
 * 特意返回「生效值」而不在控制器里回显入参：入参 overlap 允许缺省（null = 沿用文件当前重叠），
 * 若直接回显就会出现 null，而构造响应时 {@code Map.of} 拒绝 null 值（抛 NPE → 500）。
 *
 * @param chunkCount  重新分片后的知识块数
 * @param strategyKey 本次生效的分片策略 key
 * @param overlap     本次生效的重叠字符数（0 = 不重叠）
 */
public record KbRechunkResult(int chunkCount, String strategyKey, int overlap) {
}
