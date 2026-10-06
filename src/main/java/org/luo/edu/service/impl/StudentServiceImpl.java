package org.luo.edu.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import jakarta.annotation.Resource;
import org.luo.common.exception.AiBusinessException;
import org.luo.common.exception.AiErrorCode;
import org.luo.common.result.PageResult;
import org.luo.edu.dto.StudentDTO;
import org.luo.edu.entity.Score;
import org.luo.edu.entity.Student;
import org.luo.edu.mapper.StudentMapper;
import org.luo.edu.properties.EduProperties;
import org.luo.edu.service.ScoreService;
import org.luo.edu.service.StudentService;
import org.luo.edu.vo.OptionVO;
import org.luo.edu.vo.StudentVO;
import org.springframework.stereotype.Service;
import java.util.List;

/**
 * 学生业务实现：单表 CRUD/批量委托 ServiceImpl；跨表分页与下拉选项走 Mapper XML。
 * <p>
 * 唯一性校验与删除前的引用校验都收在本类，规则不散到 Controller。
 */
@Service
public class StudentServiceImpl extends ServiceImpl<StudentMapper, Student> implements StudentService {

    @Resource
    private EduProperties eduProperties;

    @Resource
    private ScoreService scoreService;

    @Override
    public PageResult<StudentVO> page(StudentDTO dto) {
        Page<StudentVO> p = dto.toPage();
        return PageResult.of(p, baseMapper.selectStudentPage(p, dto));
    }

    @Override
    public List<OptionVO> options() {
        // 下拉选项装上限：靠分页插件注入 LIMIT（false = 不额外跑 count），避免无界全表装载
        List<Student> rows = page(new Page<>(1, eduProperties.probeLimit(), false),
                new LambdaQueryWrapper<Student>().orderByAsc(Student::getId)).getRecords();
        return eduProperties.trim(rows, "student").stream()
                .map(x -> new OptionVO(x.getId().longValue(), x.getName()))
                .toList();
    }

    @Override
    public Student saveStudent(Student e) {
        requireUnique(e, null);
        baseMapper.insert(e);
        return e;
    }

    @Override
    public Student updateStudent(Student e) {
        requireUnique(e, e.getId());
        if (baseMapper.updateById(e) == 0) {
            throw new AiBusinessException(AiErrorCode.NOT_FOUND, "学生不存在：id=" + e.getId());
        }
        return e;
    }

    @Override
    public void deleteStudentById(Integer id) {
        if (scoreService.count(new LambdaQueryWrapper<Score>().eq(Score::getStudentId, id)) > 0) {
            throw new AiBusinessException(AiErrorCode.CONFLICT, "该学生已有成绩记录，不能删除");
        }
        if (baseMapper.deleteById(id) == 0) {
            throw new AiBusinessException(AiErrorCode.NOT_FOUND, "学生不存在：id=" + id);
        }
    }

    /** 学号唯一；selfId 为空表示新增。 */
    private void requireUnique(Student e, Integer selfId) {
        if (count(new LambdaQueryWrapper<Student>().ne(selfId != null, Student::getId, selfId)
                .eq(Student::getStudentNo, e.getStudentNo())) > 0) {
            throw new AiBusinessException(AiErrorCode.CONFLICT, "学号已存在");
        }
    }
}
