package org.luo.ai.dto;

import java.util.List;

/**
 * 智能体导入结果。
 * <p>
 * 逐条统计而非「成功 / 失败」两态：导入是一批不相关的写入，一条失败不该带走整批（已成功的留在库里），
 * 所以这里把 skipped 与 failed 分开——前者是「策略选择的结果」（编码已存在且选了 skip），
 * 后者是「数据本身有问题」（缺必填字段），混在一起会让人以为策略没生效。
 *
 * @param total   包内总条数
 * @param created 新建的条数
 * @param updated 覆盖更新的条数（仅 overwrite 策略会产生）
 * @param skipped 跳过的条数（编码已存在且策略为 skip）
 * @param errors  失败条目说明（形如「agentCode：原因」）；空列表表示无失败
 */
public record AgentImportResult(int total, int created, int updated, int skipped, List<String> errors) {
}
