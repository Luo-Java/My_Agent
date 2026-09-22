package org.luo.edu.controller;

import jakarta.annotation.Resource;
import org.luo.common.result.PageResult;
import org.luo.common.result.RestResult;
import org.luo.edu.dto.CourseArrangementDTO;
import org.luo.edu.entity.CourseArrangement;
import org.luo.edu.service.CourseArrangementService;
import org.luo.edu.vo.CourseArrangementVO;
import org.luo.edu.vo.OptionVO;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;

/**
 * 排课接口（/api/edu/arrangements）：命令式风格，与老师接口一致。
 * <ul>
 *   <li>{@code POST /page} —— 分页，筛选条件走 body</li>
 *   <li>{@code GET /list} —— 下拉选项（id + 可读文案）</li>
 *   <li>{@code GET /{id}} —— 单条详情</li>
 *   <li>{@code POST /save} —— 新增</li>
 *   <li>{@code PUT /update} —— 编辑，id 在 body</li>
 *   <li>{@code DELETE /delete/{id}} —— 删除</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/edu/arrangements")
public class CourseArrangementController {

    @Resource
    private CourseArrangementService service;

    @PostMapping("/page")
    public RestResult<PageResult<CourseArrangementVO>> page(@RequestBody CourseArrangementDTO dto) {
        return RestResult.ok(service.page(dto));
    }

    @GetMapping("/list")
    public RestResult<List<OptionVO>> list() {
        return RestResult.ok(service.options());
    }

    @GetMapping("/{id}")
    public RestResult<CourseArrangement> get(@PathVariable Integer id) {
        return RestResult.ok(service.getById(id));
    }

    @PostMapping("/save")
    public RestResult<CourseArrangement> save(@RequestBody CourseArrangement e) {
        return RestResult.ok(service.saveCourseArrangement(e));
    }

    @PutMapping("/update")
    public RestResult<CourseArrangement> update(@RequestBody CourseArrangement e) {
        return RestResult.ok(service.updateCourseArrangement(e));
    }

    @DeleteMapping("/delete/{id}")
    public RestResult<Void> delete(@PathVariable Integer id) {
        service.deleteCourseArrangementById(id);
        return RestResult.ok();
    }

}
