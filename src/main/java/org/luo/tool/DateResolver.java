package org.luo.tool;

import cn.hutool.json.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 日期解析工具：把自然语言里的「相对/模糊日期」与「日期范围」解析成具体日期，
 * 供天气等需要确定日期的工具在查询前调用，避免模型臆测日期。
 * <p>
 * 解析失败时不抛异常，返回含 error 字段的 JSON，由上层模型决定如何向用户追问或改用绝对日期。
 */
@Slf4j
@Service
public class DateResolver implements ToolProvider {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    @Tool(description = "把自然语言日期/日期范围解析为具体日期，必须在查询天气前使用。" +
            "支持：今天/明天/后天/昨天、下周X/本周X、X月X日、YYYY-MM-DD，以及范围（如'3月1日到3月5日''未来7天'）。" +
            "返回 JSON：单日为 {\"type\":\"single\",\"date\":\"YYYY-MM-DD\"}，范围为 {\"type\":\"range\",\"start\":\"YYYY-MM-DD\",\"end\":\"YYYY-MM-DD\"}。")
    public String resolveDate(@ToolParam(description = "用户提到的日期或日期范围的自然语言描述，例如 '下周一'、'3月1日到3月5日'、'未来7天'") String expression) {
        try {
            Resolved r = parse(expression);
            if (r == null) {
                return "{\"error\":\"无法解析日期，请使用更明确的表述，如 '明天'、'2026-08-23' 或 '3月1日到3月5日'\"}";
            }
            JSONObject obj = new JSONObject();
            if (r.start != null && r.end != null) {
                obj.set("type", "range");
                obj.set("start", r.start.format(FMT));
                obj.set("end", r.end.format(FMT));
            } else {
                obj.set("type", "single");
                obj.set("date", r.single.format(FMT));
            }
            return obj.toString();
        } catch (Exception e) {
            log.warn("日期解析失败：{}", e.getMessage());
            return "{\"error\":\"日期解析异常：" + e.getMessage() + "\"}";
        }
    }

    /** 解析结果：单日或日期范围二选一。 */
    private static final class Resolved {
        LocalDate single;
        LocalDate start;
        LocalDate end;

        Resolved(LocalDate single) { this.single = single; }
        Resolved(LocalDate start, LocalDate end) { this.start = start; this.end = end; }
    }

    private Resolved parse(String text) {
        if (text == null || text.isBlank()) return null;
        String t = text.replaceAll("\\s+", "");
        LocalDate today = LocalDate.now();

        // 未来 N 天：today ~ today+(N-1)
        Matcher futureM = Pattern.compile("(?:未来|今后|最近|接下来)(\\d+)天").matcher(t);
        if (futureM.find()) {
            int n = Integer.parseInt(futureM.group(1));
            return new Resolved(today, today.plusDays(Math.max(0, n - 1)));
        }
        // 范围：A 到/至/~/- B（含「之间/内」后缀）
        Matcher rangeM = Pattern.compile("(.+?)(?:到|至|~|—|-)(.+?)(?:之间|内)?$").matcher(t);
        if (rangeM.find()) {
            LocalDate a = parseSingle(rangeM.group(1), today);
            LocalDate b = parseSingle(rangeM.group(2), today);
            if (a != null && b != null) {
                if (a.isAfter(b)) { LocalDate tmp = a; a = b; b = tmp; }
                return new Resolved(a, b);
            }
        }
        LocalDate single = parseSingle(t, today);
        return single == null ? null : new Resolved(single);
    }

    private LocalDate parseSingle(String t, LocalDate today) {
        if (t == null || t.isBlank()) return null;

        // 绝对日期：YYYY-MM-DD / YYYY/MM/DD / YYYY年M月D日
        Matcher abs = Pattern.compile("(\\d{4})[-/年](\\d{1,2})[-/月](\\d{1,2})日?").matcher(t);
        if (abs.find()) {
            return LocalDate.of(Integer.parseInt(abs.group(1)), Integer.parseInt(abs.group(2)), Integer.parseInt(abs.group(3)));
        }
        if (t.contains("今天") || t.contains("今日")) return today;
        if (t.contains("明天") || t.contains("明日")) return today.plusDays(1);
        if (t.contains("后天")) return today.plusDays(2);
        if (t.contains("大后天")) return today.plusDays(3);
        if (t.contains("昨天") || t.contains("昨日")) return today.minusDays(1);
        if (t.contains("前天")) return today.minusDays(2);

        // X天后 / 后X天
        Matcher after = Pattern.compile("(\\d+|[一二三四五六七八九十]+)天[后以]").matcher(t);
        if (after.find()) return today.plusDays(toInt(after.group(1)));
        Matcher after2 = Pattern.compile("后(\\d+|[一二三四五六七八九十]+)天").matcher(t);
        if (after2.find()) return today.plusDays(toInt(after2.group(1)));
        // X天前 / 前X天
        Matcher before = Pattern.compile("(\\d+|[一二三四五六七八九十]+)天前").matcher(t);
        if (before.find()) return today.minusDays(toInt(before.group(1)));
        Matcher before2 = Pattern.compile("前(\\d+|[一二三四五六七八九十]+)天").matcher(t);
        if (before2.find()) return today.minusDays(toInt(before2.group(1)));

        // 下周X / 本周X
        Matcher week = Pattern.compile("([下本])(?:个?)(?:星期|周|礼拜)([一二三四五六日天])").matcher(t);
        if (week.find()) {
            DayOfWeek dw = toDow(week.group(2));
            if (dw == null) return null;
            if ("下".equals(week.group(1))) {
                int diff = dw.getValue() - today.getDayOfWeek().getValue();
                if (diff <= 0) diff += 7;
                return today.plusDays(diff);
            } else { // 本周：以本周一为基准
                return today.with(DayOfWeek.MONDAY).plusDays(dw.getValue() - 1);
            }
        }

        // X月X日 / X月X号（今年）
        Matcher md = Pattern.compile("(\\d{1,2}|[一二三四五六七八九十]+)月(\\d{1,2}|[一二三四五六七八九十]+)[日号]").matcher(t);
        if (md.find()) {
            int m = toInt(md.group(1));
            int d = toInt(md.group(2));
            if (m >= 1 && m <= 12 && d >= 1 && d <= 31) {
                LocalDate cand = LocalDate.of(today.getYear(), m, d);
                // 若明显已过去一年以上，顺延到下一年
                if (cand.isBefore(today.minusYears(1))) cand = cand.plusYears(1);
                return cand;
            }
        }
        return null;
    }

    private int toInt(String s) {
        if (s == null) return 0;
        if (s.matches("\\d+")) return Integer.parseInt(s);
        return chineseToNum(s);
    }

    /** 把中文数字（零~九十九）转为阿拉伯数字；无法识别返回 0。用于解析「X月X日」中的中文月份/日。 */
    private int chineseToNum(String s) {
        if (s == null || s.isEmpty()) return 0;
        if (!s.contains("十")) {
            int sum = 0;
            for (char c : s.toCharArray()) sum = sum * 10 + cnDigit(c);
            return sum;
        }
        String[] parts = s.split("十", -1);
        int tens = parts[0].isEmpty() ? 1 : cnDigit(parts[0].charAt(0));
        int ones = parts[1].isEmpty() ? 0 : cnDigit(parts[1].charAt(0));
        return tens * 10 + ones;
    }

    private int cnDigit(char c) {
        switch (c) {
            case '零': return 0;
            case '一': return 1;
            case '二': case '两': return 2;
            case '三': return 3;
            case '四': return 4;
            case '五': return 5;
            case '六': return 6;
            case '七': return 7;
            case '八': return 8;
            case '九': return 9;
            default: return 0;
        }
    }

    private DayOfWeek toDow(String s) {
        switch (s) {
            case "一": return DayOfWeek.MONDAY;
            case "二": return DayOfWeek.TUESDAY;
            case "三": return DayOfWeek.WEDNESDAY;
            case "四": return DayOfWeek.THURSDAY;
            case "五": return DayOfWeek.FRIDAY;
            case "六": return DayOfWeek.SATURDAY;
            case "日": case "天": return DayOfWeek.SUNDAY;
            default: return null;
        }
    }
}
