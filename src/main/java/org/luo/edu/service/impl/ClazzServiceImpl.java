package org.luo.edu.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import jakarta.annotation.Resource;
import org.luo.common.exception.AiBusinessException;
import org.luo.common.exception.AiErrorCode;
import org.luo.common.result.PageResult;
import org.luo.edu.dto.ClazzDTO;
import org.luo.edu.entity.Clazz;
import org.luo.edu.entity.Course;
import org.luo.edu.entity.Exam;
import org.luo.edu.entity.Student;
import org.luo.edu.mapper.ClazzMapper;
import org.luo.edu.properties.EduProperties;
import org.luo.edu.service.ClazzService;
import org.luo.edu.service.CourseService;
import org.luo.edu.service.ExamService;
import org.luo.edu.service.StudentService;
import org.luo.edu.vo.ClazzVO;
import org.luo.edu.vo.OptionVO;
import org.springframework.stereotype.Service;
import java.util.List;

/**
 * 班级业务实现：单表 CRUD/批量委托 ServiceImpl；跨表分页与下拉选项走 Mapper XML。
 * <p>
 * 唯一性校验与删除前的引用校验都收在本类，规则不散到 Controller。
 */
@Service
public class ClazzServiceImpl extends ServiceImpl<ClazzMapper, Clazz> implements ClazzService {

    @Resource
    private EduProperties eduProperties;

    @Resource
    private StudentService studentService;

    @Resource
    private CourseService courseService;

    @Resource
    private ExamService examService;

    @Override
    public PageResult<ClazzVO> page(ClazzDTO dto) {
        Page<ClazzVO> p = dto.toPage();
        return PageResult.of(p, baseMapper.selectClazzPage(p, dto));
    }

    @Override
    public List<OptionVO> options() {
        // 下拉选项装上限：靠分页插件注入 LIMIT（false = 不额外跑 count），避免无界全表装载
        List<Clazz> rows = page(new Page<>(1, eduProperties.probeLimit(), false),
                new LambdaQueryWrapper<Clazz>().orderByAsc(Clazz::getId)).getRecords();
        return eduProperties.trim(rows, "clazz").stream()
                .map(x -> new OptionVO(x.getId().longValue(), x.getName()))
                .toList();
    }

    @Override
    public Clazz saveClazz(Clazz e) {
        requireUnique(e, null);
        baseMapper.insert(e);
        return e;
    }

    @Override
    public Clazz updateClazz(Clazz e) {
        requireUnique(e, e.getId());
        if (baseMapper.updateById(e) == 0) {
            throw new AiBusinessException(AiErrorCode.NOT_FOUND, "班级不存在：id=" + e.getId());
        }
        return e;
    }

    @Override
    public void deleteClazzById(Integer id) {
        if (studentService.count(new LambdaQueryWrapper<Student>().eq(Student::getClassId, id)) > 0) {
            throw new AiBusinessException(AiErrorCode.CONFLICT, "该班级下仍有学生，不能删除");
        }
        if (courseService.count(new LambdaQueryWrapper<Course>().eq(Course::getClassId, id)) > 0) {
            throw new AiBusinessException(AiErrorCode.CONFLICT, "该班级已被课程引用，不能删除");
        }
        if (examService.count(new LambdaQueryWrapper<Exam>().eq(Exam::getClassId, id)) > 0) {
            throw new AiBusinessException(AiErrorCode.CONFLICT, "该班级已被考试引用，不能删除");
        }
        if (baseMapper.deleteById(id) == 0) {
            throw new AiBusinessException(AiErrorCode.NOT_FOUND, "班级不存在：id=" + id);
        }
    }

    /** 班级名称唯一；selfId 为空表示新增。 */
    private void requireUnique(Clazz e, Integer selfId) {
        if (count(new LambdaQueryWrapper<Clazz>().ne(selfId != null, Clazz::getId, selfId)
                .eq(Clazz::getName, e.getName())) > 0) {
            throw new AiBusinessException(AiErrorCode.CONFLICT, "班级名称已存在");
        }
    }
}
