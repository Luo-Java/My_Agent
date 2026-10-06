package org.luo.edu.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.luo.edu.dto.ScoreDTO;
import org.luo.edu.dto.ScoreDetailDTO;
import org.luo.edu.dto.ScoreStatsDTO;
import org.luo.edu.entity.Score;
import org.luo.edu.vo.OptionVO;
import org.luo.edu.vo.ScoreDetailVO;
import org.luo.edu.vo.ScoreStatsVO;
import org.luo.edu.vo.ScoreVO;
import java.util.List;

/** 成绩 Mapper：基础 CRUD 走 BaseMapper；分页、下拉与只读关联查询的 SQL 都在 mapper/edu/ScoreMapper.xml。 */
@Mapper
public interface ScoreMapper extends BaseMapper<Score> {

    /** 成绩分页（join 出可读名，SQL 见 XML）。筛选条件由 dto 携带。 */
    List<ScoreVO> selectScorePage(Page<ScoreVO> page, @Param("dto") ScoreDTO dto);

    /**
     * 下拉选项：id + 可读文案（SQL 里 concat 拼好）。
     * <p>
     * 首参固定为分页对象：XML 里没有 LIMIT，由 {@code PaginationInnerInterceptor} 注入，
     * 调用方用 {@code new Page<>(1, 上限, false)} 把「无界查询」变成有界（false = 不额外跑 count）。
     */
    List<OptionVO> selectOptions(Page<OptionVO> page);

    /** 学生成绩明细分页（跨表 join 出可读名称）。筛选条件由 dto 携带，keyword 模糊匹配学生姓名或学号。 */
    List<ScoreDetailVO> selectScoreDetail(Page<ScoreDetailVO> page, @Param("dto") ScoreDetailDTO dto);

    /** 成绩统计分页（按考试聚合人数/均分/最高/最低）。筛选条件由 dto 携带。 */
    List<ScoreStatsVO> selectStats(Page<ScoreStatsVO> page, @Param("dto") ScoreStatsDTO dto);
}
