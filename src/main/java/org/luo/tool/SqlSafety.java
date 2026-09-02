package org.luo.tool;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SQL 安全校验工具（SqlQueryTool / SqlSchemaTool 共用）。
 * <p>
 * 业务表与 agent 系统表在<b>同一库</b>，因此必须用白名单显式放行业务表，
 * 防止模型越权读取 conversation / chat_message / agent 等系统表。
 * 只读校验：仅允许 SELECT / WITH 开头的查询，去注释去字符串后按关键字黑名单拦截写操作。
 */
public final class SqlSafety {

    /** 允许访问的业务表（白名单）。SQL 中出现的所有表名都必须在这里。 */
    public static final Set<String> ALLOWED_TABLES = Set.of(
            "student", "class", "teacher", "subject", "course", "score");

    /** 写/危险操作关键字黑名单（去注释去字符串后的小写语句中若含即为非法）。 */
    private static final String[] FORBIDDEN = {"insert", "update", "delete", "drop", "alter",
            "create", "truncate", "set ", "grant", "revoke", "merge", "replace", "call",
            "exec", "lock", "unlock", "use ", "begin", "commit", "rollback",
            // 文件读写与 DoS：SELECT ... INTO OUTFILE/DUMPFILE 可写服务器文件（若 DB 账号有 FILE 权限），
            // SLEEP()/BENCHMARK() 可做延时攻击；LOAD_FILE() 可读服务器文件。
            "into outfile", "into dumpfile", "outfile", "dumpfile", "load_file",
            "sleep(", "benchmark(", "get_lock(", "release_lock("};

    /** 提取表名：FROM / JOIN 后紧跟的标识符（支持反引号）。 */
    private static final Pattern TABLE_PATTERN =
            Pattern.compile("(?i)\\b(?:from|join)\\s+`?([a-zA-Z_][a-zA-Z0-9_]*)`?");

    private SqlSafety() {
    }

    /**
     * 校验 SQL 是否为「只读查询」。返回 null 表示通过；返回非空字符串表示拦截原因。
     * 校验前先剥离注释与字符串字面量，避免列名/数据中出现的关键字造成误判。
     */
    public static String readOnlyVerdict(String sql) {
        String cleaned = stripCommentsAndStrings(sql).toLowerCase().trim();
        if (cleaned.isEmpty()) {
            return "SQL 为空或不合法。";
        }
        // 仅允许以 select / with 开头（with 用于 CTE 只读查询）
        if (!cleaned.startsWith("select") && !cleaned.startsWith("with")) {
            return "只允许 SELECT 或 WITH 开头的只读查询。";
        }
        for (String kw : FORBIDDEN) {
            if (cleaned.contains(kw)) {
                return "检测到不允许的关键字「" + kw.trim() + "」，仅允许只读查询。";
            }
        }
        return null;
    }

    /**
     * 校验 SQL 涉及的表是否都在业务白名单内。
     * 返回 null 表示通过；返回非空字符串表示拦截原因（含越权的表名）。
     */
    public static String tableVerdict(String sql) {
        List<String> tables = extractTables(sql);
        if (tables.isEmpty()) {
            return null; // 提取不到表名（如 SELECT 1），放行由数据库报错
        }
        for (String t : tables) {
            if (!ALLOWED_TABLES.contains(t)) {
                return "表「" + t + "」不在允许访问的业务表范围内（仅可查询：student/class/teacher/subject/course/score），" +
                        "禁止查询系统表。";
            }
        }
        return null;
    }

    /** 从 SQL 中提取 FROM / JOIN 出现的表名（去重、保持出现顺序）。 */
    public static List<String> extractTables(String sql) {
        List<String> result = new ArrayList<>();
        if (sql == null) return result;
        Matcher m = TABLE_PATTERN.matcher(sql);
        while (m.find()) {
            String t = m.group(1).toLowerCase();
            if (!result.contains(t)) {
                result.add(t);
            }
        }
        return result;
    }

    /** 去掉 SQL 中的行注释(--)、块注释(/* *\/)和单引号字符串字面量，便于做关键字黑名单检查。 */
    public static String stripCommentsAndStrings(String sql) {
        StringBuilder sb = new StringBuilder();
        int i = 0, n = sql.length();
        while (i < n) {
            char c = sql.charAt(i);
            if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {        // 行注释
                while (i < n && sql.charAt(i) != '\n') i++;
            } else if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {  // 块注释
                i += 2;
                while (i < n && !(sql.charAt(i) == '*' && i + 1 < n && sql.charAt(i + 1) == '/')) i++;
                if (i < n) i += 2;
            } else if (c == '\'') {                                         // 字符串字面量
                i++;
                while (i < n && sql.charAt(i) != '\'') {
                    if (sql.charAt(i) == '\\') i++;
                    i++;
                }
                i++; // 跳过结束引号
            } else {
                sb.append(c);
                i++;
            }
        }
        return sb.toString();
    }
}
