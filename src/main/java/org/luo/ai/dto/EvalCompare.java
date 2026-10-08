package org.luo.ai.dto;

import java.util.List;

/**
 * 两个批次的对比结果 —— 本功能的<b>核心产出</b>：改完 {@code prompts.yaml} 跑一遍、和上一次对比，
 * {@code broken} 就是「这次改坏了哪些用例」。
 * 以<b>用例名</b>为身份对齐两个批次（故改名会被视作「一增一删」，这是刻意的：改名多半意味着换了断言意图）。
 *
 * @param fromBatch   基准批次 ID
 * @param toBatch     对比批次 ID
 * @param fromPassed  基准批次通过数
 * @param fromTotal   基准批次用例数
 * @param toPassed    对比批次通过数
 * @param toTotal     对比批次用例数
 * @param fixed       由失败转为通过的用例名（改好了）
 * @param broken      由通过转为失败的用例名（改坏了 —— 最需要关注的一档）
 * @param stillFailed 两次都失败
 * @param onlyFrom    仅在基准批次出现（用例被删）
 * @param onlyTo      仅在对比批次出现（新增用例）
 */
public record EvalCompare(String fromBatch, String toBatch,
                          int fromPassed, int fromTotal, int toPassed, int toTotal,
                          List<String> fixed, List<String> broken, List<String> stillFailed,
                          List<String> onlyFrom, List<String> onlyTo) {
}
