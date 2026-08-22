package org.luo.tool;

import cn.hutool.http.HttpRequest;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 天气查询工具（基于 Open-Meteo 公开接口，无需 API Key）。
 * <p>
 * 提供两个 @Tool 方法：单日查询与日期范围查询。内部完成「城市→经纬度」地理编码、
 * 调用预报接口、把 WMO weather_code 映射为中文并整理为易读文本三件事，
 * 即「日期解析 → 查询 → 结果处理」中的后两步。城市地理编码结果带缓存。
 */
@Slf4j
@Service
public class WeatherTools implements ToolProvider {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final String GEO_URL = "https://geocoding-api.open-meteo.com/v1/search";
    private static final String FC_URL = "https://api.open-meteo.com/v1/forecast";
    private static final int TIMEOUT = 3000;

    /** 城市 → 经纬度缓存（线程安全）。 */
    private final Map<String, double[]> geoCache = new ConcurrentHashMap<>();

    @Tool(description = "查询某个城市在指定单日的天气。city 为城市名（如 北京、上海、杭州），date 为 YYYY-MM-DD 格式的具体日期。" +
            "返回该日天气摘要（天气状况、最高/最低气温）。必须先由 resolveDate 得到具体日期后再调用。")
    public String queryWeatherByDate(
            @ToolParam(description = "城市名称，如 北京、上海、广州") String city,
            @ToolParam(description = "具体日期，格式 YYYY-MM-DD，例如 2026-08-23") String date) {
        return doQuery(city, date, date);
    }

    @Tool(description = "查询某个城市在指定日期范围内的天气（含起止两天）。city 为城市名，startDate/endDate 为 YYYY-MM-DD 格式。" +
            "返回区间内每日天气摘要。必须先由 resolveDate 得到具体日期范围后再调用。")
    public String queryWeatherByRange(
            @ToolParam(description = "城市名称，如 北京、上海、广州") String city,
            @ToolParam(description = "起始日期，格式 YYYY-MM-DD") String startDate,
            @ToolParam(description = "结束日期，格式 YYYY-MM-DD") String endDate) {
        return doQuery(city, startDate, endDate);
    }

    private String doQuery(String city, String startDate, String endDate) {
        if (city == null || city.isBlank() || startDate == null || startDate.isBlank()
                || endDate == null || endDate.isBlank()) {
            return "缺少必要参数（city/startDate/endDate），无法查询天气。";
        }
        try {
            LocalDate s = LocalDate.parse(startDate, FMT);
            LocalDate e = LocalDate.parse(endDate, FMT);
            if (e.isBefore(s)) { LocalDate tmp = s; s = e; e = tmp; } // 容错：起止颠倒自动纠正

            double[] coord = geocode(city);
            if (coord == null) {
                return "未找到城市「" + city + "」的地理位置，请检查城市名是否正确（如 北京、上海市）。";
            }
            String url = FC_URL + "?latitude=" + coord[0] + "&longitude=" + coord[1]
                    + "&daily=weather_code,temperature_2m_max,temperature_2m_min"
                    + "&timezone=Asia/Shanghai&start_date=" + s.format(FMT) + "&end_date=" + e.format(FMT);
            String resp = httpGet(url);
            return formatForecast(city, coord, resp, s, e);
        } catch (Exception ex) {
            log.warn("天气查询失败：{}", ex.getMessage());
            return "天气查询失败：" + ex.getMessage();
        }
    }

    /** 发起 GET 并取响应体。显式禁用压缩（Accept-Encoding: identity），
     *  规避 Hutool 5.8.38 在 Open-Meteo（Cloudflare）偶发 gzip 响应下解压抛
     *  ZipException: invalid stored block lengths 的问题。 */
    private String httpGet(String url) {
        return HttpRequest.get(url)
                .header("Accept-Encoding", "identity")
                .timeout(TIMEOUT)
                .execute()
                .body();
    }

    /** 城市地理编码：返回 [latitude, longitude]，失败返回 null。结果缓存。 */
    private double[] geocode(String city) {
        double[] cached = geoCache.get(city);
        if (cached != null) return cached;
        try {
            String url = GEO_URL + "?name=" + URLEncoder.encode(city, StandardCharsets.UTF_8)
                    + "&count=1&language=zh&format=json";
            String resp = httpGet(url);
            JSONObject obj = JSONUtil.parseObj(resp);
            JSONArray results = obj.getJSONArray("results");
            if (results == null || results.isEmpty()) return null;
            JSONObject r0 = results.getJSONObject(0);
            double[] c = new double[]{ r0.getDouble("latitude"), r0.getDouble("longitude") };
            geoCache.put(city, c);
            return c;
        } catch (Exception e) {
            log.warn("地理编码失败：{}", e.getMessage());
            return null;
        }
    }

    /** 把 Open-Meteo 返回的 daily 数据整理为逐日中文摘要。 */
    private String formatForecast(String city, double[] coord, String resp, LocalDate s, LocalDate e) {
        JSONObject obj = JSONUtil.parseObj(resp);
        JSONObject daily = obj.getJSONObject("daily");
        if (daily == null) return "未获取到「" + city + "」的天气数据，可能是日期超出可查询范围。";
        JSONArray times = daily.getJSONArray("time");
        JSONArray codes = daily.getJSONArray("weather_code");
        JSONArray tmax = daily.getJSONArray("temperature_2m_max");
        JSONArray tmin = daily.getJSONArray("temperature_2m_min");
        if (times == null || times.isEmpty()) return "「" + city + "」在指定日期范围内无天气数据。";

        StringBuilder sb = new StringBuilder();
        sb.append("城市：").append(city)
                .append("（纬度 ").append(coord[0]).append("，经度 ").append(coord[1]).append("）\n");
        int shown = 0;
        for (int i = 0; i < times.size(); i++) {
            LocalDate d = LocalDate.parse(times.getStr(i), FMT);
            if (d.isBefore(s) || d.isAfter(e)) continue; // 只保留区间内的日期
            sb.append(d.format(FMT)).append("  ")
                    .append(weatherText(codes.getInt(i))).append("  ")
                    .append("最高 ").append(tmax.getDouble(i).intValue()).append("°C / 最低 ")
                    .append(tmin.getDouble(i).intValue()).append("°C\n");
            shown++;
        }
        if (shown == 0) return "「" + city + "」在 " + s.format(FMT) + " ~ " + e.format(FMT) + " 范围内无天气数据。";
        return sb.toString();
    }

    /** WMO weather_code → 中文天气状况。 */
    private static final Map<Integer, String> CODE_MAP = Map.ofEntries(
            Map.entry(0, "晴"), Map.entry(1, "大部晴朗"), Map.entry(2, "局部多云"), Map.entry(3, "阴"),
            Map.entry(45, "雾"), Map.entry(48, "雾凇"),
            Map.entry(51, "小毛雨"), Map.entry(53, "中毛雨"), Map.entry(55, "大毛雨"),
            Map.entry(56, "冻毛雨"), Map.entry(57, "冻毛雨"),
            Map.entry(61, "小雨"), Map.entry(63, "中雨"), Map.entry(65, "大雨"),
            Map.entry(66, "冻雨"), Map.entry(67, "冻雨"),
            Map.entry(71, "小雪"), Map.entry(73, "中雪"), Map.entry(75, "大雪"), Map.entry(77, "米雪"),
            Map.entry(80, "小阵雨"), Map.entry(81, "中阵雨"), Map.entry(82, "大阵雨"),
            Map.entry(85, "小阵雪"), Map.entry(86, "大阵雪"),
            Map.entry(95, "雷暴"), Map.entry(96, "雷暴伴冰雹"), Map.entry(99, "雷暴伴冰雹")
    );

    private String weatherText(int code) {
        return CODE_MAP.getOrDefault(code, "未知(代码" + code + ")");
    }
}
