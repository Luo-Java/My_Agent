package org.luo.tool;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SQL 安全校验工具（SqlQueryTool / SqlSchemaTool 共用）。
 * <p>
 * 业务表与 agent 系统表在<b>同一库</b>，故必须用白名单显式放行业务表，防止模型越权读 conversation /
 * chat_message / agent 等系统表。只读校验：仅允许 SELECT / WITH 开头，去注释去字符串后按关键字黑名单拦写操作。
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

    /**
     * 提取 FROM / JOIN 之后的整段「表引用」（直到下一个子句关键字、右括号或结尾），再由
     * {@link #extractTables} 按逗号切分、逐段取首个标识符。
     * <p>
     * <b>为什么不是「FROM 后紧跟的第一个标识符」</b>：那样 {@code FROM student, conversation} 只会提取到
     * {@code student}，逗号后的系统表被静默放行——白名单形同虚设。三个终止条件各有用途：
     * 子句关键字在「别名 + 多表 JOIN」场景正确收口；{@code )} 用于看穿子查询
     * （如 {@code FROM (SELECT id FROM chat_message) t}，漏掉即等于放行）；字符串结尾对应简单查询。
     * 字符类剔除括号，让外层 {@code FROM (SELECT …)} 本身不匹配，内部真正的 FROM 由正则单独扫到。
     * <p>
     * <b>已知限制（有意保留，属「过严」而非「漏洞」）</b>：CTE 名会被当表名校验，故
     * {@code WITH t AS (…) SELECT * FROM t} 会因 {@code t} 不在白名单被拒；放行 CTE 名需要额外解析
     * WITH 定义反而扩大放行面，模型改写为子查询即可，代价可控。
     */
    private static final Pattern TABLE_CLAUSE_PATTERN = Pattern.compile(
            "(?i)\\b(?:from|join)\\s+([^()]+?)(?=\\b(?:where|group|order|having|limit|union|on|join"
                    + "|from|left|right|inner|outer|cross|natural|using)\\b|\\)|$)");

    /** 表引用片段里的首个标识符（即表名，允许反引号包裹，忽略其后的别名）。 */
    private static final Pattern TABLE_NAME_PATTERN =
            Pattern.compile("^`?([a-zA-Z_][a-zA-Z0-9_]*)`?");

    private SqlSafety() {
    }

    /**
     * 校验 SQL 是否为「只读查询」。返回 null 表示通过，非空字符串为拦截原因。
     * 校验前先剥注释与字符串字面量，避免列名/数据中的关键字造成误判。
     */
    public static String readOnlyVerdict(String sql) {
        // 可执行注释必须先于「剥离注释」检查：其内容会被数据库执行、却会被剥离掉，
        // 导致「校验看到的文本」与「数据库执行的文本」不是同一段——天然绕过通道（见 containsExecutableComment）。
        if (containsExecutableComment(sql)) {
            return "SQL 中不允许出现 MySQL 版本注释或优化器提示（以 /*! 或 /*+ 开头的块注释）："
                    + "其内容会被数据库执行、却在校验时被当作注释剥除，无法安全校验。";
        }
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

    /** 校验 SQL 涉及的表是否都在业务白名单内。返回 null 表示通过，非空字符串为拦截原因（含越权的表名）。 */
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

    /**
     * 从 SQL 提取 FROM / JOIN 出现的表名（去重、保持出现顺序）。支持逗号多表与带别名的表——
     * 只取每段的<b>首个</b>标识符，别名自然被忽略。
     */
    public static List<String> extractTables(String sql) {
        List<String> result = new ArrayList<>();
        if (sql == null) return result;
        Matcher m = TABLE_CLAUSE_PATTERN.matcher(sql);
        while (m.find()) {
            for (String part : m.group(1).split(",")) {
                Matcher name = TABLE_NAME_PATTERN.matcher(part.trim());
                if (name.find()) {
                    String t = name.group(1).toLowerCase();
                    if (!result.contains(t)) {
                        result.add(t);
                    }
                }
            }
        }
        return result;
    }

    /**
     * 是否含「数据库会执行、但注释剥离逻辑会删掉」的注释：MySQL 的<b>版本注释</b>
     * （{@code /} + {@code *} + {@code !} 开头的块注释）与<b>优化器提示</b>（{@code /} + {@code *} + {@code +} 开头）
     * 在服务端会被当真实 SQL 执行。因 {@link #stripCommentsAndStrings} 会删掉整段，白名单与黑名单都看不到其内容
     * ——无法「还原后再校验」，只能直接拒绝（正常业务查询不需要这两种注释，误拦代价仅是换个写法）。
     * 扫描时跳过单引号字符串字面量，避免数据里恰好出现该片段造成误报。
     */
    private static boolean containsExecutableComment(String sql) {
        if (sql == null) return false;
        int i = 0, n = sql.length();
        while (i < n) {
            char c = sql.charAt(i);
            if (c == '\'') {                       // 字符串字面量：整体跳过
                i++;
                while (i < n && sql.charAt(i) != '\'') {
                    if (sql.charAt(i) == '\\') i++;
                    i++;
                }
                i++; // 跳过结束引号
            } else if (c == '/' && i + 1 < n && (sql.charAt(i + 1) == '!' || sql.charAt(i + 1) == '+')) {
                return true;
            } else {
                i++;
            }
        }
        return false;
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
