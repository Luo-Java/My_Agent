package org.luo.edu.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.luo.edu.dto.ClazzDTO;
import org.luo.edu.entity.Clazz;
import org.luo.edu.vo.ClazzVO;
import java.util.List;

/** 班级 Mapper：基础 CRUD 走 BaseMapper，跨表分页与下拉选项见 mapper/edu/ClazzMapper.xml。 */
@Mapper
public interface ClazzMapper extends BaseMapper<Clazz> {

    /** 班级分页（join 出可读名，SQL 见 XML）。筛选条件由 dto 携带。 */
    List<ClazzVO> selectClazzPage(Page<ClazzVO> page, @Param("dto") ClazzDTO dto);
}
