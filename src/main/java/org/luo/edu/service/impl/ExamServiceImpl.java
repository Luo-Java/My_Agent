package org.luo.edu.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import jakarta.annotation.Resource;
import org.luo.common.exception.AiBusinessException;
import org.luo.common.exception.AiErrorCode;
import org.luo.common.result.PageResult;
import org.luo.edu.dto.ExamDTO;
import org.luo.edu.entity.Exam;
import org.luo.edu.entity.Score;
import org.luo.edu.mapper.ExamMapper;
import org.luo.edu.service.ExamService;
import org.luo.edu.service.ScoreService;
import org.luo.edu.vo.ExamVO;
import org.luo.edu.vo.OptionVO;
import org.springframework.stereotype.Service;
import java.util.List;

/**
 * 考试业务实现：单表 CRUD/批量委托 ServiceImpl；跨表分页与下拉选项走 Mapper XML。
 * <p>
 * 唯一性校验与删除前的引用校验都收在本类，规则不散到 Controller。
 */
@Service
public class ExamServiceImpl extends ServiceImpl<ExamMapper, Exam> implements ExamService {

    @Resource
    private ScoreService scoreService;

    @Override
    public PageResult<ExamVO> page(ExamDTO dto) {
        Page<ExamVO> p = dto.toPage();
        return PageResult.of(p, baseMapper.selectExamPage(p, dto));
    }

    @Override
    public List<OptionVO> options() {
        return baseMapper.selectOptions();
    }

    @Override
    public Exam saveExam(Exam e) {
        requireUnique(e, null);
        baseMapper.insert(e);
        return e;
    }

    @Override
    public Exam updateExam(Exam e) {
        requireUnique(e, e.getId());
        if (baseMapper.updateById(e) == 0) {
            throw new AiBusinessException(AiErrorCode.NOT_FOUND, "考试不存在：id=" + e.getId());
        }
        return e;
    }

    @Override
    public void deleteExamById(Integer id) {
        if (scoreService.count(new LambdaQueryWrapper<Score>().eq(Score::getExamId, id)) > 0) {
            throw new AiBusinessException(AiErrorCode.CONFLICT, "该考试已有成绩记录，不能删除");
        }
        if (baseMapper.deleteById(id) == 0) {
            throw new AiBusinessException(AiErrorCode.NOT_FOUND, "考试不存在：id=" + id);
        }
    }

    /** 同一班级、同一学期、同一科目、同一天只允许一场考试；selfId 为空表示新增。 */
    private void requireUnique(Exam e, Integer selfId) {
        if (count(new LambdaQueryWrapper<Exam>()
                .ne(selfId != null, Exam::getId, selfId)
                .eq(Exam::getClassId, e.getClassId())
                .eq(Exam::getSubjectId, e.getSubjectId())
                .eq(Exam::getSemesterId, e.getSemesterId())
                .eq(Exam::getExamDate, e.getExamDate())) > 0) {
            throw new AiBusinessException(AiErrorCode.CONFLICT, "该班级该科目在该学期同一天已有考试");
        }
    }
}
