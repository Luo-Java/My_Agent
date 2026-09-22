package org.luo.edu.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import jakarta.annotation.Resource;
import org.luo.common.exception.AiBusinessException;
import org.luo.common.exception.AiErrorCode;
import org.luo.common.result.PageResult;
import org.luo.edu.dto.CourseDTO;
import org.luo.edu.entity.Course;
import org.luo.edu.entity.CourseArrangement;
import org.luo.edu.mapper.CourseMapper;
import org.luo.edu.service.CourseArrangementService;
import org.luo.edu.service.CourseService;
import org.luo.edu.vo.CourseVO;
import org.luo.edu.vo.OptionVO;
import org.springframework.stereotype.Service;
import java.util.List;

/**
 * 课程业务实现：单表 CRUD/批量委托 ServiceImpl；跨表分页与下拉选项走 Mapper XML。
 * <p>
 * 唯一性校验与删除前的引用校验都收在本类，规则不散到 Controller。
 */
@Service
public class CourseServiceImpl extends ServiceImpl<CourseMapper, Course> implements CourseService {

    @Resource
    private CourseArrangementService arrangementService;

    @Override
    public PageResult<CourseVO> page(CourseDTO dto) {
        Page<CourseVO> p = dto.toPage();
        return PageResult.of(p, baseMapper.selectCoursePage(p, dto));
    }

    @Override
    public List<OptionVO> options() {
        return baseMapper.selectOptions();
    }

    @Override
    public Course saveCourse(Course e) {
        requireUnique(e, null);
        baseMapper.insert(e);
        return e;
    }

    @Override
    public Course updateCourse(Course e) {
        requireUnique(e, e.getId());
        if (baseMapper.updateById(e) == 0) {
            throw new AiBusinessException(AiErrorCode.NOT_FOUND, "课程不存在：id=" + e.getId());
        }
        return e;
    }

    @Override
    public void deleteCourseById(Integer id) {
        if (arrangementService.count(new LambdaQueryWrapper<CourseArrangement>().eq(CourseArrangement::getCourseId, id)) > 0) {
            throw new AiBusinessException(AiErrorCode.CONFLICT, "该课程已有排课记录，不能删除");
        }
        if (baseMapper.deleteById(id) == 0) {
            throw new AiBusinessException(AiErrorCode.NOT_FOUND, "课程不存在：id=" + id);
        }
    }

    /** 同一班级、同一学期、同一科目只允许开一门课；selfId 为空表示新增。 */
    private void requireUnique(Course e, Integer selfId) {
        if (count(new LambdaQueryWrapper<Course>()
                .ne(selfId != null, Course::getId, selfId)
                .eq(Course::getClassId, e.getClassId())
                .eq(Course::getSubjectId, e.getSubjectId())
                .eq(Course::getSemesterId, e.getSemesterId())) > 0) {
            throw new AiBusinessException(AiErrorCode.CONFLICT, "该班级在该学期已开设同一科目的课程");
        }
    }
}
