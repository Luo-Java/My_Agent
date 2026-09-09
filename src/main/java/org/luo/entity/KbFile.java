package org.luo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 知识库文件（KbFile）：每个知识库维护的文件列表中的一项。
 * <p>
 * 文件是知识的上传 / 管理单元：用户向某知识库上传文件 → 后台解析文本 → 分块向量化写入
 * {@code kb_chunk}（每块的 source = 文件名），同时在此登记一条文件记录（大小 / 分块数等）。
 * 删除文件 = 删除该记录并级联删除它名下全部知识块。同一库内文件名唯一
 * （{@code uk_kb_file(kb_id, file_name)}），同名重传按「替换」处理：先清旧块再写新块。
 */
@Data
@NoArgsConstructor
@TableName("kb_file")
public class KbFile {

    /** 文件 ID，数据库自增主键。 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 所属知识库 ID（关联 kb.id）。 */
    private Long kbId;

    /** 文件名（含扩展名；同一库内唯一，且与名下知识块的 source 一致，用于级联删除）。 */
    private String fileName;

    /** 文件类型（扩展名小写，如 pdf/docx/xlsx/txt/md）。 */
    private String fileType;

    /** 入库该文件所用的分片策略 key（fixed/paragraph/recursive/markdown），默认 recursive。 */
    private String chunkStrategy;

    /** 相邻知识块之间的重叠字符数（0 = 不重叠；默认 60；重新分片时沿用该值，除非显式改传）。 */
    private Integer chunkOverlap;

    /** 文件大小（字节）。 */
    private Long sizeBytes;

    /** 该文件解析出的知识块数量（冗余字段，上传时写入、删单块时同步扣减）。 */
    private Integer chunkCount;

    /** 入库时的解析原文：用于不重传文件直接切换分片策略（重新分片），仅写不读（列表接口会清空不回传）。 */
    private String rawText;

    /** 上传时间。 */
    private LocalDateTime createdAt;

    /** 最后更新时间（同名重传时刷新）。 */
    private LocalDateTime updatedAt;
}
