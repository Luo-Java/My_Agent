package org.luo.edu.controller;

import jakarta.annotation.Resource;
import org.luo.common.result.RestResult;
import org.luo.edu.service.EduMetaService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.Map;

/**
 * 教务看板接口（/api/edu/dashboard）：跨表聚合统计。
 * <ul>
 *   <li>{@code GET /dashboard} —— 看板统计：总量 + 各年级学生分布 + 各科老师分布</li>
 * </ul>
 * 这里只留看板；外键下拉选项由各表自己的 {@code GET /list} 提供，不再集中出一个字典接口。
 */
@RestController
@RequestMapping("/api/edu")
public class EduMetaController {

    @Resource
    private EduMetaService metaService;

    @GetMapping("/dashboard")
    public RestResult<Map<String, Object>> dashboard() {
        return RestResult.ok(metaService.dashboard());
    }
}
