package org.luo.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.luo.ai.entity.EvalCaseEntity;

/**
 * 库内回归用例 Mapper。
 * <p>
 * 查询都是「按 enabled 取全部 / 按 id 删」这类单表操作，{@link BaseMapper} 足够，无需显式 SQL（故无 XML）。
 */
@Mapper
public interface EvalCaseMapper extends BaseMapper<EvalCaseEntity> {
}
