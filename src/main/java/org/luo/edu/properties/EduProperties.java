package org.luo.edu.properties;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 教务模块配置（{@code agent.edu.*}）。
 * <p>
 * 只放一件事：各 {@code GET /api/edu/**&#47;list} 下拉选项接口的返回上限。
 * <p>
 * <b>为什么需要上限：</b>这些接口的语义是「返回全部可选项」，前端拿它渲染筛选下拉，所以 SQL 里
 * 原本没有任何上界。但 score 这类高基数表（成绩 5 万行，且 selectOptions 要 join 5 张表再 concat
 * 拼文案）一旦全量装载，既慢又占内存 —— 这是「无界查询」，不是「数据量大」。把上界集中到这里，
 * 避免在每个 Mapper / Service 里各写一份魔数。
 * <p>
 * <b>截断必须留痕：</b>超出上限时裁剪并 WARN。下拉少了几项属于功能缺失（用户选不到靠后的项），
 * 不能静默 —— 否则只会被当成「数据本来就这么点」。
 */
@Slf4j
@ConfigurationProperties(prefix = "agent.edu")
public class EduProperties {

    /** 单个下拉选项接口返回的最大条数（非正数按 1 处理）。 */
    private int optionsMaxRows = 500;

    public int getOptionsMaxRows() {
        return optionsMaxRows;
    }

    public void setOptionsMaxRows(int optionsMaxRows) {
        this.optionsMaxRows = optionsMaxRows;
    }

    /** SQL 层要取的条数：上限 + 1。多取的那一条只用于判断「到底有没有被截断」。 */
    public int probeLimit() {
        return maxRows() + 1;
    }

    /**
     * 按上限裁剪结果，超限时留 WARN。
     *
     * @param rows  SQL 查出的行（调用方应查 {@link #probeLimit()} 条以便探测截断）
     * @param table 表名，仅用于日志定位
     */
    public <T> List<T> trim(List<T> rows, String table) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        int max = maxRows();
        if (rows.size() <= max) {
            return rows;
        }
        log.warn("{} 的下拉选项超过上限 {} 条，已截断：下拉里会缺少靠后的选项。"
                + "调大 agent.edu.options-max-rows，或改用对应的 /page 接口按条件筛选。", table, max);
        return new ArrayList<>(rows.subList(0, max));
    }

    /** 生效的上限：非正数（含配置未绑定时的 0）一律按 1 —— 不能让 LIMIT 变成「不限」。 */
    private int maxRows() {
        return optionsMaxRows > 0 ? optionsMaxRows : 1;
    }
}
