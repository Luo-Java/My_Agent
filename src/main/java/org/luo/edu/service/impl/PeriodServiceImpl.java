package org.luo.edu.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import jakarta.annotation.Resource;
import org.luo.common.exception.AiBusinessException;
import org.luo.common.exception.AiErrorCode;
import org.luo.common.result.PageResult;
import org.luo.edu.dto.PeriodDTO;
import org.luo.edu.entity.CourseArrangement;
import org.luo.edu.entity.Period;
import org.luo.edu.mapper.PeriodMapper;
import org.luo.edu.service.CourseArrangementService;
import org.luo.edu.service.PeriodService;
import org.luo.edu.vo.OptionVO;
import org.springframework.stereotype.Service;
import java.util.List;

/**
 * 节次业务实现：单表 CRUD/批量委托 ServiceImpl；分页走条件构造器。
 * <p>
 * 唯一性校验与删除前的引用校验都收在本类，规则不散到 Controller。
 */
@Service
public class PeriodServiceImpl extends ServiceImpl<PeriodMapper, Period> implements PeriodService {

    @Resource
    private CourseArrangementService arrangementService;

    @Override
    public PageResult<Period> page(PeriodDTO dto) {
        LambdaQueryWrapper<Period> w = new LambdaQueryWrapper<Period>()
                .eq(dto.getPeriodNo() != null, Period::getPeriodNo, dto.getPeriodNo())
                .orderByDesc(Period::getId);
        Page<Period> p = dto.toPage();
        return PageResult.of(super.page(p, w));
    }

    @Override
    public List<OptionVO> options() {
        return list(new LambdaQueryWrapper<Period>().orderByAsc(Period::getId)).stream()
                .map(x -> new OptionVO(x.getId().longValue(), "第" + x.getPeriodNo() + "节 " + x.getStartTime() + "-" + x.getEndTime()))
                .toList();
    }

    @Override
    public Period savePeriod(Period e) {
        requireUnique(e, null);
        baseMapper.insert(e);
        return e;
    }

    @Override
    public Period updatePeriod(Period e) {
        requireUnique(e, e.getId());
        if (baseMapper.updateById(e) == 0) {
            throw new AiBusinessException(AiErrorCode.NOT_FOUND, "节次不存在：id=" + e.getId());
        }
        return e;
    }

    @Override
    public void deletePeriodById(Integer id) {
        if (arrangementService.count(new LambdaQueryWrapper<CourseArrangement>().eq(CourseArrangement::getStartPeriodId, id)) > 0) {
            throw new AiBusinessException(AiErrorCode.CONFLICT, "该节次已被排课用为开始节次，不能删除");
        }
        if (arrangementService.count(new LambdaQueryWrapper<CourseArrangement>().eq(CourseArrangement::getEndPeriodId, id)) > 0) {
            throw new AiBusinessException(AiErrorCode.CONFLICT, "该节次已被排课用为结束节次，不能删除");
        }
        if (baseMapper.deleteById(id) == 0) {
            throw new AiBusinessException(AiErrorCode.NOT_FOUND, "节次不存在：id=" + id);
        }
    }

    /** 节次序号唯一；selfId 为空表示新增。 */
    private void requireUnique(Period e, Integer selfId) {
        if (count(new LambdaQueryWrapper<Period>().ne(selfId != null, Period::getId, selfId)
                .eq(Period::getPeriodNo, e.getPeriodNo())) > 0) {
            throw new AiBusinessException(AiErrorCode.CONFLICT, "节次序号已存在");
        }
    }
}
