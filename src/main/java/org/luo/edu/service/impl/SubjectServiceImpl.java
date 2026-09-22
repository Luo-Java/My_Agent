package org.luo.edu.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import jakarta.annotation.Resource;
import org.luo.common.exception.AiBusinessException;
import org.luo.common.exception.AiErrorCode;
import org.luo.common.result.PageResult;
import org.luo.edu.dto.SubjectDTO;
import org.luo.edu.entity.Course;
import org.luo.edu.entity.Exam;
import org.luo.edu.entity.Subject;
import org.luo.edu.entity.Teacher;
import org.luo.edu.mapper.SubjectMapper;
import org.luo.edu.service.CourseService;
import org.luo.edu.service.ExamService;
import org.luo.edu.service.SubjectService;
import org.luo.edu.service.TeacherService;
import org.luo.edu.vo.OptionVO;
import org.springframework.stereotype.Service;
import java.util.List;

/**
 * 科目业务实现：单表 CRUD/批量委托 ServiceImpl；分页走条件构造器。
 * <p>
 * 唯一性校验与删除前的引用校验都收在本类，规则不散到 Controller。
 */
@Service
public class SubjectServiceImpl extends ServiceImpl<SubjectMapper, Subject> implements SubjectService {

    @Resource
    private TeacherService teacherService;

    @Resource
    private CourseService courseService;

    @Resource
    private ExamService examService;

    @Override
    public PageResult<Subject> page(SubjectDTO dto) {
        LambdaQueryWrapper<Subject> w = new LambdaQueryWrapper<Subject>()
                .like(hasText(dto.getName()), Subject::getName, dto.getName())
                .orderByDesc(Subject::getId);
        Page<Subject> p = dto.toPage();
        return PageResult.of(super.page(p, w));
    }

    @Override
    public List<OptionVO> options() {
        return list(new LambdaQueryWrapper<Subject>().orderByAsc(Subject::getId)).stream()
                .map(x -> new OptionVO(x.getId().longValue(), x.getName()))
                .toList();
    }

    @Override
    public Subject saveSubject(Subject e) {
        requireUnique(e, null);
        baseMapper.insert(e);
        return e;
    }

    @Override
    public Subject updateSubject(Subject e) {
        requireUnique(e, e.getId());
        if (baseMapper.updateById(e) == 0) {
            throw new AiBusinessException(AiErrorCode.NOT_FOUND, "科目不存在：id=" + e.getId());
        }
        return e;
    }

    @Override
    public void deleteSubjectById(Integer id) {
        if (teacherService.count(new LambdaQueryWrapper<Teacher>().eq(Teacher::getSubjectId, id)) > 0) {
            throw new AiBusinessException(AiErrorCode.CONFLICT, "该科目已被老师引用为主教学科，不能删除");
        }
        if (courseService.count(new LambdaQueryWrapper<Course>().eq(Course::getSubjectId, id)) > 0) {
            throw new AiBusinessException(AiErrorCode.CONFLICT, "该科目已被课程引用，不能删除");
        }
        if (examService.count(new LambdaQueryWrapper<Exam>().eq(Exam::getSubjectId, id)) > 0) {
            throw new AiBusinessException(AiErrorCode.CONFLICT, "该科目已被考试引用，不能删除");
        }
        if (baseMapper.deleteById(id) == 0) {
            throw new AiBusinessException(AiErrorCode.NOT_FOUND, "科目不存在：id=" + id);
        }
    }

    /** 科目名称与编码都唯一；selfId 为空表示新增。 */
    private void requireUnique(Subject e, Integer selfId) {
        if (count(new LambdaQueryWrapper<Subject>().ne(selfId != null, Subject::getId, selfId)
                .eq(Subject::getName, e.getName())) > 0) {
            throw new AiBusinessException(AiErrorCode.CONFLICT, "科目名称已存在");
        }

        if (count(new LambdaQueryWrapper<Subject>().ne(selfId != null, Subject::getId, selfId)
                .eq(Subject::getCode, e.getCode())) > 0) {
            throw new AiBusinessException(AiErrorCode.CONFLICT, "科目编码已存在");
        }
    }

    /** 字符串筛选条件：null 与纯空白都视为不筛。 */
    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
