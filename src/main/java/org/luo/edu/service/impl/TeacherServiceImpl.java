package org.luo.edu.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import jakarta.annotation.Resource;
import org.luo.common.exception.AiBusinessException;
import org.luo.common.exception.AiErrorCode;
import org.luo.common.result.PageResult;
import org.luo.edu.dto.TeacherDTO;
import org.luo.edu.entity.Clazz;
import org.luo.edu.entity.Course;
import org.luo.edu.entity.Teacher;
import org.luo.edu.mapper.TeacherMapper;
import org.luo.edu.service.ClazzService;
import org.luo.edu.service.CourseService;
import org.luo.edu.service.TeacherService;
import org.luo.edu.vo.OptionVO;
import org.luo.edu.vo.TeacherVO;
import org.springframework.stereotype.Service;
import java.util.List;

/**
 * 老师业务实现：单表 CRUD/批量委托 ServiceImpl；跨表分页与下拉选项走 Mapper XML。
 * <p>
 * 唯一性校验与删除前的引用校验都收在本类，规则不散到 Controller。
 */
@Service
public class TeacherServiceImpl extends ServiceImpl<TeacherMapper, Teacher> implements TeacherService {

    @Resource
    private ClazzService clazzService;

    @Resource
    private CourseService courseService;

    @Override
    public PageResult<TeacherVO> page(TeacherDTO dto) {
        Page<TeacherVO> p = dto.toPage();
        return PageResult.of(p, baseMapper.selectTeacherPage(p, dto));
    }

    @Override
    public List<OptionVO> options() {
        return list(new LambdaQueryWrapper<Teacher>().orderByAsc(Teacher::getId)).stream()
                .map(x -> new OptionVO(x.getId().longValue(), x.getName()))
                .toList();
    }

    @Override
    public Teacher saveTeacher(Teacher e) {
        requireUnique(e, null);
        baseMapper.insert(e);
        return e;
    }

    @Override
    public Teacher updateTeacher(Teacher e) {
        requireUnique(e, e.getId());
        if (baseMapper.updateById(e) == 0) {
            throw new AiBusinessException(AiErrorCode.NOT_FOUND, "老师不存在：id=" + e.getId());
        }
        return e;
    }

    @Override
    public void deleteTeacherById(Integer id) {
        if (clazzService.count(new LambdaQueryWrapper<Clazz>().eq(Clazz::getHeadTeacherId, id)) > 0) {
            throw new AiBusinessException(AiErrorCode.CONFLICT, "该老师担任了班级的班主任，不能删除");
        }
        if (courseService.count(new LambdaQueryWrapper<Course>().eq(Course::getTeacherId, id)) > 0) {
            throw new AiBusinessException(AiErrorCode.CONFLICT, "该老师担任了课程的授课老师，不能删除");
        }
        if (baseMapper.deleteById(id) == 0) {
            throw new AiBusinessException(AiErrorCode.NOT_FOUND, "老师不存在：id=" + id);
        }
    }

    /** 老师姓名唯一；selfId 为空表示新增。 */
    private void requireUnique(Teacher e, Integer selfId) {
        if (count(new LambdaQueryWrapper<Teacher>().ne(selfId != null, Teacher::getId, selfId)
                .eq(Teacher::getName, e.getName())) > 0) {
            throw new AiBusinessException(AiErrorCode.CONFLICT, "老师姓名已存在");
        }
    }
}
