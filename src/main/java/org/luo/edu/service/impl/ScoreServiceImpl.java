package org.luo.edu.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.luo.common.exception.AiBusinessException;
import org.luo.common.exception.AiErrorCode;
import org.luo.common.result.PageResult;
import org.luo.edu.dto.ScoreDTO;
import org.luo.edu.entity.Score;
import org.luo.edu.mapper.ScoreMapper;
import org.luo.edu.service.ScoreService;
import org.luo.edu.vo.OptionVO;
import org.luo.edu.vo.ScoreVO;
import org.springframework.stereotype.Service;
import java.util.List;

/**
 * 成绩业务实现：单表 CRUD/批量委托 ServiceImpl；跨表分页与下拉选项走 Mapper XML。
 * <p>
 * 唯一性校验与删除前的引用校验都收在本类，规则不散到 Controller。
 */
@Service
public class ScoreServiceImpl extends ServiceImpl<ScoreMapper, Score> implements ScoreService {

    @Override
    public PageResult<ScoreVO> page(ScoreDTO dto) {
        Page<ScoreVO> p = dto.toPage();
        return PageResult.of(p, baseMapper.selectScorePage(p, dto));
    }

    @Override
    public List<OptionVO> options() {
        return baseMapper.selectOptions();
    }

    @Override
    public Score saveScore(Score e) {
        requireUnique(e, null);
        baseMapper.insert(e);
        return e;
    }

    @Override
    public Score updateScore(Score e) {
        requireUnique(e, e.getId());
        if (baseMapper.updateById(e) == 0) {
            throw new AiBusinessException(AiErrorCode.NOT_FOUND, "成绩不存在：id=" + e.getId());
        }
        return e;
    }

    @Override
    public void deleteScoreById(Long id) {
        if (baseMapper.deleteById(id) == 0) {
            throw new AiBusinessException(AiErrorCode.NOT_FOUND, "成绩不存在：id=" + id);
        }
    }

    /** 同一学生的同一场考试只允许一条成绩；selfId 为空表示新增。 */
    private void requireUnique(Score e, Long selfId) {
        if (count(new LambdaQueryWrapper<Score>()
                .ne(selfId != null, Score::getId, selfId)
                .eq(Score::getStudentId, e.getStudentId())
                .eq(Score::getExamId, e.getExamId())) > 0) {
            throw new AiBusinessException(AiErrorCode.CONFLICT, "该学生这场考试的成绩已录入");
        }
    }
}
