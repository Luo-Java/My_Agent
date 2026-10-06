package org.luo.ai.dto;

import java.util.List;

/**
 * 智能体导入请求体。
 *
 * @param agents     待导入的可移植定义（通常是导出的 JSON 数组原样回传，也允许手工编写）
 * @param onConflict 编码冲突策略：{@code skip}（默认）= 跳过已存在的同编码智能体；
 *                   {@code overwrite} = 用包内定义覆盖它（保留本地 id 与创建时间，只改内容列）。
 *                   两种策略都<b>不会</b>新建重复编码的记录。
 */
public record AgentImportRequest(List<AgentPortable> agents, String onConflict) {
}
