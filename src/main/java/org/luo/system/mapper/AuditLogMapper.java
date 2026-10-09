package org.luo.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.luo.system.entity.AuditLog;

/**
 * 管理操作审计 Mapper。
 * <p>
 * <b>刻意只有 {@link BaseMapper} 而没有自定义 SQL</b>：审计表只增不改（没有任何更新/删除入口），
 * 读取是单一维度的分页 + 按动作筛选，Wrapper 足够表达。等出现「按操作者 + 时间段 + 对象」这类组合查询
 * 再落 XML —— 那时的查询条件才有类型化投影的价值（本项目 mapper 里显式 SQL 一律落 XML，禁注解）。
 */
@Mapper
public interface AuditLogMapper extends BaseMapper<AuditLog> {
}
