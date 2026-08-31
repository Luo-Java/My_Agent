package org.luo.tool;

import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 教育数据分析 SQL 执行工具（只读）。
 * <p>
 * 仅允许对业务数据表执行 <b>SELECT / 含 WITH 的只读查询</b>（表与 agent 系统同库，见 {@link SqlSafety#ALLOWED_TABLES}），
 * 严禁任何写操作，且拒绝多语句（含 {@code ;} 的 SQL），从工具层堵死模型生成破坏性 SQL 的可能。
 * 安全校验（只读关键字 + 业务表白名单）统一走 {@link SqlSafety}。
 * <p>
 * 失败时返回<b>分类诊断</b>（列名错/表错/语法错/歧义等 + SQL 涉及表的真实列名提示），
 * 给模型的反思纠错回路提供燃料。同时带两层重试保护：
 * <ul>
 *   <li>同一 SQL 连续失败 {@value #MAX_SAME_SQL_FAILS} 次直接拒绝，逼模型换写法；</li>
 *   <li>{@value #FAIL_WINDOW_MS / 1000} 秒窗口内失败超 {@value #MAX_WINDOW_FAILS} 次触发熔断，防止死循环烧 token。</li>
 * </ul>
 * 结果统一以 JSON 数组（每行一个对象）字符串返回给模型，最多 {@value #MAX_ROWS} 行。
 */
@Slf4j
@Service
public class SqlQueryTool implements ToolProvider {

    /** 单次查询返回的最大行数，防止超大结果撑爆上下文。 */
    private static final int MAX_ROWS = 200;

    /** 同一 SQL（规范化后）连续失败上限，超过则拒绝执行。 */
    private static final int MAX_SAME_SQL_FAILS = 3;

    /** 全局失败熔断窗口（毫秒）。 */
    private static final long FAIL_WINDOW_MS = 60_000;

    /** 窗口内失败次数上限，超过则熔断。 */
    private static final int MAX_WINDOW_FAILS = 10;

    private final JdbcTemplate jdbcTemplate;

    /** 同一 SQL 的失败计数（key=规范化 SQL），成功后清除。 */
    private final Map<String, AtomicInteger> failCounts = new ConcurrentHashMap<>();

    /** 近期失败时间戳队列，用于窗口熔断；执行成功后清空（证明模型已走出失败循环）。 */
    private final ConcurrentLinkedDeque<Long> recentFailTimes = new ConcurrentLinkedDeque<>();

    public SqlQueryTool(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.jdbcTemplate.setMaxRows(MAX_ROWS);
    }

    @Tool(description = "在教育业务表（student/class/teacher/subject/course/score）上执行【只读】SQL 查询并返回结果（JSON 数组，每行一个对象，最多 200 行）。\n" +
            "仅支持 SELECT 语句或以 WITH 开头的 CTE 查询；严禁 INSERT/UPDATE/DELETE/DROP/ALTER 等写操作，且不支持多语句。\n" +
            "执行前若不确定列名/表名，请先调用 describe_table、sample_rows 确认，再调用 validate_sql 预检。\n" +
            "若执行失败会返回错误原因与列名提示，请据此修正后重试（同一 SQL 最多重试 3 次）。\n" +
            "常用表：student(学生)/class(班级)/teacher(老师)/subject(科目)/course(课程)/score(成绩)。")
    public String query(
            @ToolParam(description = "一条只读 SELECT（或 WITH）SQL，例如 SELECT subject_id, AVG(score) FROM score JOIN course ON score.course_id=course.id GROUP BY subject_id") String sql) {
        if (sql == null || sql.isBlank()) {
            return "错误：SQL 不能为空。";
        }
        String trimmed = sql.trim();
        log.info("SQL 工具收到查询：\n{}", trimmed);
        // 1) 拒绝多语句（含分号视为多语句，防止链式注入）
        if (trimmed.contains(";")) {
            log.warn("SQL 工具拦截：检测到多语句，SQL=\n{}", trimmed);
            return "安全拦截：不支持多语句查询（SQL 中不能包含 ';'）。请只提交单条 SELECT 查询。";
        }
        // 2) 只读校验
        String verdict = SqlSafety.readOnlyVerdict(trimmed);
        if (verdict != null) {
            log.warn("SQL 工具拦截：{}，SQL=\n{}", verdict, trimmed);
            return "安全拦截：" + verdict;
        }
        // 3) 业务表白名单校验（与系统同库，禁止触碰 conversation/chat_message/agent 等系统表）
        verdict = SqlSafety.tableVerdict(trimmed);
        if (verdict != null) {
            log.warn("SQL 工具拦截：{}，SQL=\n{}", verdict, trimmed);
            return "安全拦截：" + verdict;
        }
        // 4) 重试保护：同一条 SQL 反复失败 → 拒绝；窗口内失败过多 → 熔断
        String key = sqlKey(trimmed);
        AtomicInteger cnt = failCounts.get(key);
        if (cnt != null && cnt.get() >= MAX_SAME_SQL_FAILS) {
            log.warn("SQL 工具拒绝：同一条 SQL 已连续失败 {} 次，SQL=\n{}", MAX_SAME_SQL_FAILS, trimmed);
            return "已拦截：该 SQL 已连续失败 " + MAX_SAME_SQL_FAILS + " 次，请换一种写法（先 describe_table 确认列名、validate_sql 预检），" +
                    "或停止重试、向用户说明需要补充什么信息。";
        }
        long start = System.currentTimeMillis();
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(trimmed);
            long cost = System.currentTimeMillis() - start;
            // 执行成功：清除该 SQL 的失败计数与熔断窗口（证明模型已走出失败循环）
            failCounts.remove(key);
            recentFailTimes.clear();
            if (rows.isEmpty()) {
                log.info("SQL 工具执行完成：0 行，耗时 {} ms", cost);
                return "查询成功，但没有匹配的数据行。若怀疑是过滤条件过严或取值口径不对，可先 sample_rows 确认字段取值。";
            }
            // 行数截断提示：若恰好达到上限，可能是被截断
            boolean truncated = rows.size() >= MAX_ROWS;
            List<Map<String, Object>> safe = new ArrayList<>(rows.size());
            for (Map<String, Object> row : rows) {
                Map<String, Object> m = new LinkedHashMap<>();
                for (Map.Entry<String, Object> e : row.entrySet()) {
                    m.put(e.getKey(), toJsonSafe(e.getValue()));
                }
                safe.add(m);
            }
            String json = JSONUtil.toJsonStr(safe);
            log.info("SQL 工具执行完成：{} 行，耗时 {} ms", rows.size(), cost);
            return "查询成功，返回 " + rows.size() + " 行" + (truncated ? "（已达到 " + MAX_ROWS + " 行上限，结果可能不完整）" : "") + "：\n" + json;
        } catch (Exception ex) {
            long cost = System.currentTimeMillis() - start;
            String msg = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            // 记录失败：先清理过期时间戳，再判断窗口熔断
            long now = System.currentTimeMillis();
            recentFailTimes.addLast(now);
            while (!recentFailTimes.isEmpty() && now - recentFailTimes.peekFirst() > FAIL_WINDOW_MS) {
                recentFailTimes.pollFirst();
            }
            int fails = failCounts.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
            log.warn("SQL 工具执行失败（同 SQL 第 {} 次）：{}，耗时 {} ms，SQL=\n{}", fails, msg, cost, trimmed);
            if (recentFailTimes.size() >= MAX_WINDOW_FAILS) {
                recentFailTimes.clear();
                failCounts.clear();
                log.warn("SQL 工具熔断：{} 秒内失败超过 {} 次，暂停 SQL 执行", FAIL_WINDOW_MS / 1000, MAX_WINDOW_FAILS);
                return "已熔断：检测到连续多次 SQL 执行失败，已暂停执行。请停下当前循环，向用户说明遇到的问题，不要继续重试。";
            }
            return diagnoseFailure(trimmed, msg, fails);
        }
    }

    /**
     * 把执行失败信息转成「可行动的诊断」：按 MySQL 错误类型分类给建议，并附上 SQL 涉及表的真实列名，
     * 让模型不需要再靠猜就能修正。这是反思纠错回路的核心燃料。
     */
    private String diagnoseFailure(String sql, String msg, int fails) {
        StringBuilder sb = new StringBuilder();
        sb.append("查询执行失败（同 SQL 第 ").append(fails).append(" 次，最多 ").append(MAX_SAME_SQL_FAILS).append(" 次）：").append(msg).append("\n");
        String low = msg.toLowerCase();
        if (low.contains("unknown column")) {
            sb.append("原因：引用了不存在的列名。请调用 describe_table 查看该表真实列名后重写 SQL。");
        } else if (low.contains("unknown table")) {
            sb.append("原因：表名不存在或不在业务白名单内。可用表：student/class/teacher/subject/course/score。");
        } else if (low.contains("syntax")) {
            sb.append("原因：SQL 语法错误，请检查逗号、括号、引号与关键字拼写，或先 validate_sql 预检。");
        } else if (low.contains("ambiguous")) {
            sb.append("原因：列名有歧义（多表 JOIN 时有同名列），请为列名加表别名限定，如 t.name。");
        } else if (low.contains("group by")) {
            sb.append("原因：GROUP BY 与 SELECT 列不匹配，请把聚合外的列都加入 GROUP BY。");
        } else {
            sb.append("请检查表名、列名与 SQL 语法，可先 validate_sql 预检。");
        }
        List<String> tables = SqlSafety.extractTables(sql);
        if (!tables.isEmpty()) {
            sb.append("\nSQL 涉及的表：").append(String.join(", ", tables))
                    .append("。可调用 describe_table(\"表名\") 查看真实列定义。");
        }
        return sb.toString();
    }

    /** 规范化 SQL 作为失败计数 key：小写 + 压缩空白。 */
    private static String sqlKey(String sql) {
        return sql.replaceAll("\\s+", " ").trim().toLowerCase();
    }

    /** 把任意 JDBC 返回值转为 JSON 安全的对象（原生类型原样、其余转字符串、null 保持 null）。 */
    private Object toJsonSafe(Object v) {
        if (v == null) return null;
        if (v instanceof String || v instanceof Number || v instanceof Boolean) return v;
        if (v instanceof byte[]) return "<blob>";
        return v.toString();
    }
}
