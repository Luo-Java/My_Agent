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

/**
 * 教育数据分析 Schema 工具：给模型「真实表结构 / 样例数据 / SQL 预检」三种能力，
 * 是 SQL 自主规划与反思纠错的燃料——
 * <ul>
 *   <li>describe_table：从 information_schema 读真实列定义，永不过时（不再靠工具描述里写死的摘要）；</li>
 *   <li>sample_rows：看几行真实数据，确认枚举取值（如 exam_type 实际是「期中」还是「期中考试」）；</li>
 *   <li>validate_sql：用 EXPLAIN 预检语法与表/列存在性，不真正执行，把错误挡在执行之前。</li>
 * </ul>
 * 三个工具都只读、且表名/涉及表全部走 {@link SqlSafety} 白名单校验，禁止触碰系统表。
 */
@Slf4j
@Service
public class SqlSchemaTool implements ToolProvider {

    /** 样例数据最大返回行数（模型只需要几行确认取值，多了浪费 token）。 */
    private static final int MAX_SAMPLE_ROWS = 5;

    private final JdbcTemplate jdbcTemplate;

    public SqlSchemaTool(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Tool(description = "查看某张业务表的真实结构：列名、类型、是否可空、是否主键、列注释。\n" +
            "在不确定列名、或 SQL 报 Unknown column 时调用。可用表：student/class/teacher/subject/course/score。")
    public String describe_table(
            @ToolParam(description = "表名，仅限 student/class/teacher/subject/course/score 之一") String tableName) {
        String verdict = tableNameVerdict(tableName);
        if (verdict != null) return verdict;
        try {
            List<Map<String, Object>> cols = jdbcTemplate.queryForList(
                    "SELECT COLUMN_NAME AS col, COLUMN_TYPE AS type, IS_NULLABLE AS nullable, " +
                            "COLUMN_KEY AS `key`, COLUMN_COMMENT AS comment " +
                            "FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? " +
                            "ORDER BY ORDINAL_POSITION", tableName);
            if (cols.isEmpty()) {
                return "表「" + tableName + "」不存在，请检查表名（可用表：student/class/teacher/subject/course/score）。";
            }
            List<Map<String, Object>> safe = toJsonSafeList(cols);
            return "表 " + tableName + " 的列定义（共 " + cols.size() + " 列）：\n" + JSONUtil.toJsonStr(safe);
        } catch (Exception ex) {
            log.warn("describe_table 失败：table={}，原因={}", tableName, ex.getMessage());
            return "查询表结构失败：" + ex.getMessage();
        }
    }

    @Tool(description = "查看某张业务表的几行真实样例数据，用于确认字段取值格式（如 exam_type 的实际枚举值、日期格式）。" +
            "在不确定取值、或查询结果为空时调用。可用表：student/class/teacher/subject/course/score。")
    public String sample_rows(
            @ToolParam(description = "表名，仅限 student/class/teacher/subject/course/score 之一") String tableName,
            @ToolParam(description = "返回行数，默认 3，最大 5") Integer limit) {
        String verdict = tableNameVerdict(tableName);
        if (verdict != null) return verdict;
        int n = (limit == null || limit < 1) ? 3 : Math.min(limit, MAX_SAMPLE_ROWS);
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT * FROM `" + tableName + "` LIMIT " + n);
            if (rows.isEmpty()) {
                return "表「" + tableName + "」为空，没有数据。";
            }
            return "表 " + tableName + " 的 " + rows.size() + " 行样例：\n" + JSONUtil.toJsonStr(toJsonSafeList(rows));
        } catch (Exception ex) {
            log.warn("sample_rows 失败：table={}，原因={}", tableName, ex.getMessage());
            return "读取样例失败：" + ex.getMessage();
        }
    }

    @Tool(description = "执行前预检一条只读 SQL（内部用 EXPLAIN，不真正执行查询）：确认语法、表名、列名是否合法，" +
            "并返回预估扫描行数与是否用到索引。执行 query 前建议先调用本工具，把错误挡在执行之前。")
    public String validate_sql(
            @ToolParam(description = "一条只读 SELECT（或 WITH）SQL") String sql) {
        if (sql == null || sql.isBlank()) {
            return "错误：SQL 不能为空。";
        }
        String trimmed = sql.trim();
        if (trimmed.contains(";")) {
            return "安全拦截：不支持多语句查询（SQL 中不能包含 ';'）。";
        }
        String verdict = SqlSafety.readOnlyVerdict(trimmed);
        if (verdict != null) return "安全拦截：" + verdict;
        verdict = SqlSafety.tableVerdict(trimmed);
        if (verdict != null) return "安全拦截：" + verdict;
        try {
            List<Map<String, Object>> plan = jdbcTemplate.queryForList("EXPLAIN " + trimmed);
            if (plan.isEmpty()) {
                return "预检通过：SQL 合法（EXPLAIN 未返回执行计划）。";
            }
            List<Map<String, Object>> brief = new ArrayList<>(plan.size());
            for (Map<String, Object> row : plan) {
                Map<String, Object> m = new LinkedHashMap<>();
                Object t = row.get("table");
                if (t != null) m.put("table", t);
                Object tp = row.get("type");
                if (tp != null) m.put("type", tp);
                Object key = row.get("key");
                if (key != null) m.put("key", key);
                Object rows = row.get("rows");
                if (rows != null) m.put("rows", rows);
                Object extra = row.get("Extra");
                if (extra != null) m.put("Extra", extra);
                brief.add(m);
            }
            return "预检通过（EXPLAIN 执行计划）：\n" + JSONUtil.toJsonStr(brief)
                    + "\n提示：rows 为预估扫描行数；type 为 ALL 且 rows 很大时说明未走索引，可优化 WHERE/JOIN 条件。";
        } catch (Exception ex) {
            log.info("validate_sql 预检未通过：SQL=\n{}，原因={}", trimmed, ex.getMessage());
            return "预检未通过：" + ex.getMessage() + "\n请修正后重新 validate_sql，或先 describe_table 确认列名。";
        }
    }

    private String tableNameVerdict(String tableName) {
        if (tableName == null || tableName.isBlank()) {
            return "错误：表名不能为空。可用表：student/class/teacher/subject/course/score。";
        }
        String t = tableName.trim().toLowerCase();
        if (!SqlSafety.ALLOWED_TABLES.contains(t)) {
            return "安全拦截：表「" + tableName + "」不在允许访问的业务表范围内（仅可查询：student/class/teacher/subject/course/score）。";
        }
        return null;
    }

    private List<Map<String, Object>> toJsonSafeList(List<Map<String, Object>> rows) {
        List<Map<String, Object>> safe = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : row.entrySet()) {
                Object v = e.getValue();
                if (v instanceof String || v instanceof Number || v instanceof Boolean) {
                    m.put(e.getKey(), v);
                } else if (v == null) {
                    m.put(e.getKey(), null);
                } else {
                    m.put(e.getKey(), v.toString());
                }
            }
            safe.add(m);
        }
        return safe;
    }
}
