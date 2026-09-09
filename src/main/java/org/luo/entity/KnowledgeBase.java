package org.luo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 知识库（Knowledge Base）实体：RAG 检索增强的知识载体。
 * <p>
 * 两类知识库，共用一张表，以 {@code agent_id} 区分：
 * <ul>
 *   <li><b>全局知识库</b>（agent_id 为 NULL）：名称「通用知识库」，所有会话默认可用；</li>
 *   <li><b>智能体专属库</b>（agent_id 非空）：与某个智能体一一对应（{@code uk_kb_agent} 唯一），
 *       管理入口在智能体的知识库卡片上。</li>
 * </ul>
 * 是否检索由<b>会话级 RAG 开关</b>（conversation.rag_enabled）决定：开启后自动检索
 * 「通用知识库 + 本轮路由到智能体时的其专属库」，关闭则不使用 RAG（无需手动选库）。
 * 知识内容以分块（{@link KnowledgeChunk}）为单位存储，每块预生成向量，检索时按余弦相似度取 TopK。
 */
@Data
@NoArgsConstructor
@TableName("kb")
public class KnowledgeBase {

    /** 知识库 ID，数据库自增主键。 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 知识库名称。 */
    private String name;

    /**
     * 归属智能体 ID（关联 agent.id）。
     * 非空 = 智能体专属库（每个智能体至多一个）；NULL = 全局知识库（名称「通用知识库」）。
     * 会话级 RAG 开关开启后自动检索全局库 + 本轮路由智能体的专属库，无需手动选库。
     */
    private Long agentId;

    /** 知识库说明（可选）。 */
    private String description;

    /** 知识块数量（冗余计数，由增删知识操作维护，仅用于列表展示）。 */
    private Integer docCount;

    /**
     * 默认分片策略 key（fixed/paragraph/recursive/markdown）。
     * 库级默认配置（知识库设置）：上传新文件未指定策略时继承，作为该库文件切块规则。
     */
    private String chunkStrategy;

    /**
     * 默认相邻块重叠字符数（0 = 不重叠）。
     * 库级默认配置（知识库设置）：上传新文件未指定重叠时继承，保证块间重复数据可被语义检索命中。
     */
    private Integer chunkOverlap;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
