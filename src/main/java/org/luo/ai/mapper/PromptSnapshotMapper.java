package org.luo.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.luo.ai.entity.PromptSnapshot;

/**
 * 提示词快照 Mapper。
 * <p>
 * 只有「按 id 倒序取最新一条」「按 id 倒序取最近 N 条」与「删掉旧行」三种操作，{@link BaseMapper}
 * 的 Wrapper 足够，无需显式 SQL（故无 XML，与 {@code ConversationFactMapper} 同约定）。
 */
@Mapper
public interface PromptSnapshotMapper extends BaseMapper<PromptSnapshot> {
}
