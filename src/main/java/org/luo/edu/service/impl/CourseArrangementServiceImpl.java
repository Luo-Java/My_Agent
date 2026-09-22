package org.luo.edu.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.luo.common.exception.AiBusinessException;
import org.luo.common.exception.AiErrorCode;
import org.luo.common.result.PageResult;
import org.luo.edu.dto.CourseArrangementDTO;
import org.luo.edu.entity.CourseArrangement;
import org.luo.edu.mapper.CourseArrangementMapper;
import org.luo.edu.service.CourseArrangementService;
import org.luo.edu.vo.CourseArrangementVO;
import org.luo.edu.vo.OptionVO;
import org.springframework.stereotype.Service;
import java.util.List;

/**
 * 排课业务实现：单表 CRUD/批量委托 ServiceImpl；跨表分页与下拉选项走 Mapper XML。
 * <p>
 * 唯一性校验与删除前的引用校验都收在本类，规则不散到 Controller。
 */
@Service
public class CourseArrangementServiceImpl extends ServiceImpl<CourseArrangementMapper, CourseArrangement> implements CourseArrangementService {

    @Override
    public PageResult<CourseArrangementVO> page(CourseArrangementDTO dto) {
        Page<CourseArrangementVO> p = dto.toPage();
        return PageResult.of(p, baseMapper.selectCourseArrangementPage(p, dto));
    }

    @Override
    public List<OptionVO> options() {
        return baseMapper.selectOptions();
    }

    @Override
    public CourseArrangement saveCourseArrangement(CourseArrangement e) {
        requireUnique(e, null);
        baseMapper.insert(e);
        return e;
    }

    @Override
    public CourseArrangement updateCourseArrangement(CourseArrangement e) {
        requireUnique(e, e.getId());
        if (baseMapper.updateById(e) == 0) {
            throw new AiBusinessException(AiErrorCode.NOT_FOUND, "排课不存在：id=" + e.getId());
        }
        return e;
    }

    @Override
    public void deleteCourseArrangementById(Integer id) {
        if (baseMapper.deleteById(id) == 0) {
            throw new AiBusinessException(AiErrorCode.NOT_FOUND, "排课不存在：id=" + id);
        }
    }

    /** 同一课程、同一天、同一开始节次只允许排一节；selfId 为空表示新增。 */
    private void requireUnique(CourseArrangement e, Integer selfId) {
        if (count(new LambdaQueryWrapper<CourseArrangement>()
                .ne(selfId != null, CourseArrangement::getId, selfId)
                .eq(CourseArrangement::getCourseId, e.getCourseId())
                .eq(CourseArrangement::getDayOfWeek, e.getDayOfWeek())
                .eq(CourseArrangement::getStartPeriodId, e.getStartPeriodId())) > 0) {
            throw new AiBusinessException(AiErrorCode.CONFLICT, "该课程在同一天该时段已有排课");
        }
    }
}
