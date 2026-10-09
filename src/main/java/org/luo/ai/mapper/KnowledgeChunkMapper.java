package org.luo.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.luo.ai.entity.KnowledgeChunk;

import java.util.List;

/**
 * 知识块表 Mapper。
 */
@Mapper
public interface KnowledgeChunkMapper extends BaseMapper<KnowledgeChunk> {

    /**
     * 知识库<b>关键词召回</b>（混合检索的字面一路）：在指定库内做多词项 {@code LIKE} 匹配，按「命中词项个数」降序。
     * <p>
     * 与向量召回的分工：向量负责「说法不同但意思相近」，本查询负责「术语、专有名词、数字这些字面精确的东西」
     * （「Q3 营收」「SQL 注入」这类问题向量常常召回不准，字面反而一击即中）。两路结果由
     * {@link org.luo.ai.util.Rrf} 融合。
     * <p>
     * 红线：① <b>有界</b> —— 必带 {@code LIMIT}（{@code kb_chunk.content} 是 TEXT，无界扫描会把整库正文拉进堆）；
     * ② 词项由调用方清洗，绑定走 {@code #{}}，<b>不拼接字符串</b>（词项来源是用户输入，不是可信数据）。
     *
     * @param kbIds 目标库 ID（非空，否则 SQL 生成空的 {@code IN ()}）
     * @param terms 已清洗的词项（非空，否则 SQL 生成空的 {@code OR} 链）
     * @param limit 候选条数上限
     * @return 命中的知识块（<b>只回检索所需列</b>，不含 {@code embedding} —— 那是 1024 维 JSON，拉回来纯属浪费）
     */
    List<KnowledgeChunk> searchByKeyword(@Param("kbIds") List<Long> kbIds,
                                        @Param("terms") List<String> terms,
                                        @Param("limit") int limit);
}
