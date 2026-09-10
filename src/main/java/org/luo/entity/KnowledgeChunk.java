package org.luo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import org.luo.enums.ChunkStrategy;
import org.luo.service.KbService;

/**
 * 知识块（Chunk）：知识库的最小检索单元。
 * <p>
 * 一段原文经分块（按所选分片策略 {@link org.luo.enums.ChunkStrategy} 切分，单块上限约 600 字符）后，
 * 由 Embedding API 生成向量存入 {@code embedding}（JSON float 数组，如 {@code [0.012, -0.034, ...]}）。
 * 检索时把用户问题向量化，与该库全部知识块做余弦相似度排序取 TopK，
 * 命中结果作为「知识库资料」注入系统提示词。
 */
@Data
@NoArgsConstructor
@TableName("kb_chunk")
public class KnowledgeChunk {

    /** 知识块 ID，数据库自增主键。 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 所属知识库 ID（关联 kb.id）。 */
    private Long kbId;

    /** 知识块原文。 */
    private String content;

    /** 来源标注（如文档名/条目名），供引用溯源与前端展示。 */
    private String source;

    /**
     * 内容向量（JSON float 数组字符串）。
     * 检索时由 {@link org.luo.service.KbService} 解析回数值数组做余弦相似度。
     */
    @TableField("embedding")
    private String embedding;

    private LocalDateTime createdAt;
}
