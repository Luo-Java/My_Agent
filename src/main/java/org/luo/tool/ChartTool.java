package org.luo.tool;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 图表工具集：
 * <ul>
 *   <li>{@link #chart_histogram}：把一组数值渲染成 Unicode 块状文本直方图（旧版，纯文本兜底）；</li>
 *   <li>{@link #chart_echarts}：生成 ECharts option JSON（柱状图/折线图/饼图/直方图），
 *       返回 ```echarts 代码块，前端识别后渲染为真正的交互图表。</li>
 * </ul>
 * <p>
 * 分桶、计数、百分比、均值/中位数等统计全部由工具精确计算，避免模型手算算术错误。
 */
@Slf4j
@Service
public class ChartTool implements ToolProvider {

    /** 条形的最大长度（个块，文本直方图用）。 */
    private static final int MAX_BAR = 20;

    /** 分桶数量上限，防止区间过大导致输出爆炸。 */
    private static final int MAX_BUCKETS = 60;

    // ==================== ECharts 图表（推荐） ====================

    /**
     * 生成 ECharts 图表 JSON：支持 bar（柱状图）、line（折线图）、pie（饼图）、histogram（直方图）。
     * 返回一个 ```echarts 代码块，模型须原样嵌入回复，前端会把它渲染成真正的交互图表。
     */
    @Tool(description = "生成 ECharts 图表 JSON（柱状图/折线图/饼图/直方图），返回一个 ```echarts 代码块，把它原样嵌入回复即可在前端渲染成真正的图表。\n" +
            "用法：先用 query 取到数据，再调用本工具生成图表。\n" +
            "参数：type 图表类型（bar 柱状图 / line 折线图 / pie 饼图 / histogram 直方图，默认 bar）；title 图表标题；\n" +
            "categories 类别数组 JSON（如 [\"一班\",\"二班\",\"三班\"]，histogram 不需要）；\n" +
            "values 数值数组 JSON（单系列数据，如 [85,90,78]）；\n" +
            "series 多系列数组 JSON（如 [{\"name\":\"语文\",\"data\":[85,90]},{\"name\":\"数学\",\"data\":[88,92]}]，提供时优先于 values）；\n" +
            "bucketSize 直方图分桶宽度（默认 10，成绩分布用 10 即可）；unit 数值单位后缀（如 分/人）。\n" +
            "histogram 类型：把原始成绩数值数组传给 values，工具自动分桶统计，无需传 categories。\n" +
            "pie 类型注意：请传 categories（各分类名称，如 [\"优秀\",\"良好\",\"及格\"]）和 values（对应数值，如 [120,200,80]），\n" +
            "或者 series 传 [{\"name\":\"优秀\",\"value\":120},{\"name\":\"良好\",\"value\":200}] 结构；\n" +
            "不要把柱状图式的多系列结构（{\"name\":...,\"data\":[...]}）传给饼图。")
    public String chart_echarts(
            @ToolParam(description = "图表类型：bar 柱状图 / line 折线图 / pie 饼图 / histogram 直方图，默认 bar") String type,
            @ToolParam(description = "图表标题，如 各班平均分对比") String title,
            @ToolParam(description = "类别数组 JSON，如 [\"一班\",\"二班\",\"三班\"]；histogram 类型不需要") String categories,
            @ToolParam(description = "数值数组 JSON，如 [85,90,78]；单系列数据，histogram 传原始数值即可") String values,
            @ToolParam(description = "多系列数组 JSON，如 [{\"name\":\"语文\",\"data\":[85,90]}]；提供时优先于 values") String series,
            @ToolParam(description = "直方图分桶宽度，仅 histogram 类型使用，默认 10") Integer bucketSize,
            @ToolParam(description = "数值单位后缀，如 分/人，显示在坐标轴名称") String unit) {
        if (title == null || title.isBlank()) {
            title = "图表";
        }
        String t = type == null ? "bar" : type.trim().toLowerCase();
        try {
            String option = switch (t) {
                case "histogram" -> buildHistogramOption(title, values, bucketSize, unit);
                case "pie" -> buildPieOption(title, categories, values, series, unit);
                case "line" -> buildCartesianOption("line", title, categories, values, series, unit);
                default -> buildCartesianOption("bar", title, categories, values, series, unit);
            };
            log.info("ECharts 图表生成完成：类型={}，标题={}", t, title);
            return "```echarts\n" + option + "\n```";
        } catch (IllegalArgumentException e) {
            log.warn("chart_echarts 参数错误：{}", e.getMessage());
            return "错误：" + e.getMessage() + "。请修正参数后重试。";
        } catch (Exception e) {
            log.warn("chart_echarts 生成失败：{}", e.getMessage(), e);
            return "错误：图表生成失败，原因=" + e.getMessage() + "。请检查 categories/values/series 是否为合法 JSON 数组。";
        }
    }

    /** 柱状图 / 折线图：笛卡尔坐标系，单系列（values）或多系列（series）。 */
    private String buildCartesianOption(String chartType, String title, String categoriesJson,
                                        String valuesJson, String seriesJson, String unit) {
        JSONArray cats = parseJsonArray(categoriesJson);
        JSONArray seriesArr = new JSONArray();
        if (seriesJson != null && !seriesJson.isBlank()) {
            JSONArray multi = JSONUtil.parseArray(seriesJson);
            for (Object o : multi) {
                JSONObject so = (JSONObject) o;
                seriesArr.add(buildSeriesItem(chartType, so.getStr("name"), so.getJSONArray("data"), unit));
            }
        } else {
            JSONArray vals = parseJsonArray(valuesJson);
            if (vals == null || vals.isEmpty()) {
                throw new IllegalArgumentException("缺少数据：请提供 values（单系列）或 series（多系列）");
            }
            seriesArr.add(buildSeriesItem(chartType, null, vals, unit));
        }
        JSONObject opt = JSONUtil.createObj();
        opt.set("title", JSONUtil.createObj().set("text", title).set("left", "center"));
        opt.set("tooltip", JSONUtil.createObj().set("trigger", "axis"));
        opt.set("grid", JSONUtil.createObj().set("left", 60).set("right", 30).set("top", 60).set("bottom", 40));
        JSONObject xAxis = JSONUtil.createObj().set("type", "category");
        if (cats != null) {
            xAxis.set("data", cats);
        }
        JSONObject yAxis = JSONUtil.createObj().set("type", "value");
        if (unit != null && !unit.isBlank()) {
            yAxis.set("name", unit);
        }
        opt.set("xAxis", xAxis);
        opt.set("yAxis", yAxis);
        opt.set("series", seriesArr);
        return opt.toString();
    }

    /** 构造单个系列。 */
    private JSONObject buildSeriesItem(String type, String name, JSONArray data, String unit) {
        JSONObject s = JSONUtil.createObj();
        s.set("type", type);
        if (name != null && !name.isBlank()) {
            s.set("name", name);
        }
        s.set("data", data);
        if (unit != null && !unit.isBlank()) {
            if (name != null && !name.isBlank()) {
                s.set("name", name + "（" + unit + "）");
            } else {
                s.set("name", unit);
            }
        }
        return s;
    }

    /** 饼图：categories（名称）+ values（数值），或 series 传 [{name,value}]。 */
    private String buildPieOption(String title, String categoriesJson, String valuesJson,
                                  String seriesJson, String unit) {
        JSONArray data = new JSONArray();
        if (seriesJson != null && !seriesJson.isBlank()) {
            JSONArray multi = JSONUtil.parseArray(seriesJson);
            for (Object o : multi) {
                JSONObject so = (JSONObject) o;
                Object value = so.get("value");
                if (value != null) {
                    data.add(JSONUtil.createObj().set("name", so.getStr("name")).set("value", value));
                    continue;
                }
                JSONArray arr = so.getJSONArray("data");
                if (arr == null || arr.isEmpty()) {
                    throw new IllegalArgumentException("饼图 series 每项需包含 value（如 {\"name\":\"优秀\",\"value\":120}）或 data 数组");
                }
                if (arr.size() == 1) {
                    data.add(JSONUtil.createObj().set("name", so.getStr("name")).set("value", arr.get(0)));
                } else {
                    // 误传柱状图多系列结构：把 data 每个值展开为独立扇形，避免丢数据
                    String base = so.getStr("name");
                    for (int i = 0; i < arr.size(); i++) {
                        data.add(JSONUtil.createObj().set("name", base + " " + (i + 1)).set("value", arr.get(i)));
                    }
                }
            }
        } else {
            JSONArray cats = parseJsonArray(categoriesJson);
            JSONArray vals = parseJsonArray(valuesJson);
            if (cats == null || vals == null || cats.isEmpty() || vals.isEmpty()) {
                throw new IllegalArgumentException("饼图需要 categories（名称）和 values（数值）两个数组");
            }
            int n = Math.min(cats.size(), vals.size());
            for (int i = 0; i < n; i++) {
                data.add(JSONUtil.createObj().set("name", cats.getStr(i)).set("value", vals.get(i)));
            }
        }
        JSONObject opt = JSONUtil.createObj();
        opt.set("title", JSONUtil.createObj().set("text", title).set("left", "center"));
        opt.set("tooltip", JSONUtil.createObj().set("trigger", "item"));
        opt.set("legend", JSONUtil.createObj().set("bottom", 0));
        JSONObject series = JSONUtil.createObj()
                .set("name", title)
                .set("type", "pie")
                .set("radius", "60%")
                .set("data", data);
        if (unit != null && !unit.isBlank()) {
            series.set("name", title + "（" + unit + "）");
        }
        opt.set("series", singleSeries(series));
        return opt.toString();
    }

    /** 直方图：对原始数值分桶统计，输出柱状图形式的 ECharts option。 */
    private String buildHistogramOption(String title, String valuesJson, Integer bucketSize, String unit) {
        List<Double> values = parseValues(valuesJson);
        if (values.isEmpty()) {
            throw new IllegalArgumentException("histogram 类型需要 values（原始数值数组 JSON，如 [78,85,92]）");
        }
        int bs = bucketSize == null ? 10 : bucketSize;
        if (bs <= 0) {
            throw new IllegalArgumentException("bucketSize 必须大于 0");
        }
        BinResult bin = bucketize(values, bs, null, null);
        JSONArray cats = new JSONArray();
        JSONArray counts = new JSONArray();
        for (int i = 0; i < bin.buckets(); i++) {
            int l = bin.lo() + i * bs;
            int h = bin.lo() + (i + 1) * bs;
            cats.add("[" + l + ", " + h + ")");
            counts.add(bin.counts()[i]);
        }
        JSONObject opt = JSONUtil.createObj();
        opt.set("title", JSONUtil.createObj().set("text", title + "（共 " + bin.total() + " 个）").set("left", "center"));
        opt.set("tooltip", JSONUtil.createObj().set("trigger", "axis"));
        opt.set("grid", JSONUtil.createObj().set("left", 60).set("right", 30).set("top", 60).set("bottom", 40));
        JSONObject xAxis = JSONUtil.createObj().set("type", "category").set("data", cats);
        if (unit != null && !unit.isBlank()) {
            xAxis.set("name", unit);
        }
        opt.set("xAxis", xAxis);
        opt.set("yAxis", JSONUtil.createObj().set("type", "value").set("name", "人数"));
        JSONObject series = JSONUtil.createObj()
                .set("name", "人数")
                .set("type", "bar")
                .set("data", counts);
        opt.set("series", singleSeries(series));
        return opt.toString();
    }

    // ==================== 文本直方图（旧版兜底） ====================

    /**
     * 把一组数值渲染成 Unicode 块状文本直方图（分布图），返回纯文本。
     * 供模型在无法渲染 ECharts 的极端场景兜底使用；一般场景优先用 {@link #chart_echarts}。
     */
    @Tool(description = "把一组数值渲染成文本直方图（分布图），返回纯文本图表（含分桶计数、百分比、均值/中位数等统计）。\n" +
            "适合「成绩分布」「直方图」等场景。用法：先用 query 查询拿到数值列表，再把数值数组（JSON 文本）传给本工具。\n" +
            "参数：title 图表标题；data 数值数组 JSON（如 [78,85,92,60,73]）；bucketSize 分桶宽度（默认 10，成绩分布用 10 即可）；\n" +
            "min/max 可指定统计区间（默认按数据自动取整桶边界）。")
    public String chart_histogram(
            @ToolParam(description = "图表标题，如 六年级期末数学成绩分布") String title,
            @ToolParam(description = "数值数组的 JSON 文本，如 [78,85,92,60,73] 或 [78.5,85,92]") String data,
            @ToolParam(description = "分桶宽度，默认 10") Integer bucketSize,
            @ToolParam(description = "统计区间下限，默认按数据最小值向下取整到桶边界") Integer min,
            @ToolParam(description = "统计区间上限，默认按数据最大值向上取整到桶边界") Integer max) {
        if (title == null || title.isBlank()) {
            title = "数值分布";
        }
        List<Double> values = parseValues(data);
        if (values.isEmpty()) {
            return "错误：data 中没有可解析的数值，请传入 JSON 数值数组（如 [78,85,92]）。";
        }
        int bs = bucketSize == null ? 10 : bucketSize;
        if (bs <= 0) {
            return "错误：bucketSize 必须大于 0。";
        }
        BinResult bin;
        try {
            bin = bucketize(values, bs, min, max);
        } catch (IllegalArgumentException e) {
            return "错误：" + e.getMessage();
        }
        double dataMin = values.stream().mapToDouble(Double::doubleValue).min().orElse(0);
        double dataMax = values.stream().mapToDouble(Double::doubleValue).max().orElse(0);
        int total = values.size();
        int maxCount = 0;
        for (int c : bin.counts()) {
            maxCount = Math.max(maxCount, c);
        }
        // 统计：均值（1 位小数）、中位数
        List<Double> sorted = new ArrayList<>(values);
        sorted.sort(Double::compareTo);
        double avg = values.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double median = sorted.size() % 2 == 1
                ? sorted.get(sorted.size() / 2)
                : (sorted.get(sorted.size() / 2 - 1) + sorted.get(sorted.size() / 2)) / 2.0;

        StringBuilder sb = new StringBuilder();
        sb.append("📊 ").append(title).append("（共 ").append(total).append(" 个数据）\n");
        sb.append(String.format("统计：最小 %.1f，最大 %.1f，平均 %.1f，中位数 %.1f\n",
                dataMin, dataMax, avg, median));
        for (int i = 0; i < bin.buckets(); i++) {
            int barLen = maxCount == 0 ? 0 : Math.max(1, Math.round(bin.counts()[i] * MAX_BAR / (float) maxCount));
            sb.append(String.format("[%4d, %4d) %s %4d 人 %5.1f%%\n",
                    bin.lo() + i * bs, bin.lo() + (i + 1) * bs,
                    "█".repeat(barLen), bin.counts()[i], bin.counts()[i] * 100.0 / total));
        }
        String out = sb.toString().stripTrailing();
        log.info("分布图工具生成完成：标题={}，数据 {} 个，区间 [{}, {})，分桶 {}",
                title, total, bin.lo(), bin.hi(), bs);
        return out;
    }

    // ==================== 公共逻辑 ====================

    /** 分桶结果。 */
    private record BinResult(int lo, int hi, int[] counts, int buckets, int total) {
    }

    /**
     * 对数值分桶：桶边界自动取整（floor(最小/bs)*bs ~ ceil(最大/bs)*bs），
     * 分桶数超过 {@link #MAX_BUCKETS} 时抛 {@link IllegalArgumentException}。
     */
    private BinResult bucketize(List<Double> values, int bs, Integer min, Integer max) {
        double dataMin = values.stream().mapToDouble(Double::doubleValue).min().orElse(0);
        double dataMax = values.stream().mapToDouble(Double::doubleValue).max().orElse(0);
        int lo = min != null ? min : (int) Math.floor(dataMin / bs) * bs;
        int hi = max != null ? max : (int) Math.ceil(dataMax / bs) * bs;
        if (hi <= lo) {
            hi = lo + bs;
        }
        int buckets = (hi - lo) / bs;
        if (buckets > MAX_BUCKETS) {
            throw new IllegalArgumentException("区间 [" + lo + ", " + hi + ") 按 " + bs + " 分桶会产生 "
                    + buckets + " 个桶（超过上限 " + MAX_BUCKETS + "），请调大 bucketSize 或缩小 min/max。");
        }
        if (buckets <= 0) {
            throw new IllegalArgumentException("区间 [" + lo + ", " + hi + ") 无法分桶，请检查 bucketSize 与数据范围。");
        }
        int[] counts = new int[buckets];
        for (double v : values) {
            int idx = (int) ((v - lo) / bs);
            if (idx < 0) {
                idx = 0;
            } else if (idx >= buckets) {
                idx = buckets - 1;
            }
            counts[idx]++;
        }
        return new BinResult(lo, hi, counts, buckets, values.size());
    }

    /** 构造仅含一个系列的数组。 */
    private JSONArray singleSeries(JSONObject s) {
        JSONArray arr = JSONUtil.createArray();
        arr.add(s);
        return arr;
    }

    /** 解析 JSON 数组；解析失败返回 null。 */
    private JSONArray parseJsonArray(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return JSONUtil.parseArray(json);
        } catch (Exception e) {
            return null;
        }
    }

    /** 解析 JSON 数值数组；解析失败返回空列表。 */
    private List<Double> parseValues(String data) {
        List<Double> result = new ArrayList<>();
        if (data == null || data.isBlank()) {
            return result;
        }
        try {
            JSONArray arr = JSONUtil.parseArray(data);
            for (int i = 0; i < arr.size(); i++) {
                Object o = arr.get(i);
                if (o == null) {
                    continue;
                }
                if (o instanceof Number n) {
                    result.add(n.doubleValue());
                } else if (o instanceof String s) {
                    result.add(Double.parseDouble(s.trim()));
                }
            }
        } catch (Exception e) {
            log.warn("图表工具：data 解析失败，data={}，原因={}", data, e.getMessage());
            return new ArrayList<>();
        }
        return result;
    }
}
