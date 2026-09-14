package org.luo.enums;
import org.luo.service.KbService;

/**
 * 知识库文件的分片策略（决定文档切成多少个知识块、在哪些边界切），单块均不超过 {@code maxChars}（默认 600 字符）：
 * <ul>
 *   <li>{@link #FIXED}：固定长度硬切，块大小均匀、无语义偏好；</li>
 *   <li>{@link #PARAGRAPH}：按空行（自然段落）切块，段落超长时内部再按固定长度切；</li>
 *   <li>{@link #RECURSIVE}：递归字符切分（参考 LangChain RecursiveCharacterTextSplitter），分隔符从粗到细
 *       逐级尝试，通用性与块质量均衡，为默认策略；</li>
 *   <li>{@link #MARKDOWN}：按 {@code # ~ ######} 标题分节，子块自带标题作上下文；无标题文本回退递归切分。</li>
 * </ul>
 * 前端经 {@code GET /api/kb/chunk-strategies} 拉取策略渲染选项；上传接口用 {@code chunkStrategy} 指定，
 * 文件记录会保存所选策略，之后可对已入库文件「重新分片」动态切换（见 KbService#rechunkFile）。
 */
public enum ChunkStrategy {

    FIXED("fixed", "固定长度", "按固定字符数硬切，块大小完全均匀，无语义偏好"),
    PARAGRAPH("paragraph", "按段落", "按空行分自然段，段落保留完整语义；超长段内再按固定长度切"),
    RECURSIVE("recursive", "递归字符", "段落→行→句子→标点逐级切分，尽量在语义边界断句（默认，最通用）"),
    MARKDOWN("markdown", "Markdown 标题", "按 # 标题分节，标题随内容入块（子块自带上下文）；无标题自动回退递归");

    /** 策略唯一标识（入库 / 接口参数用，如 recursive）。 */
    private final String key;

    /** 展示名（前端下拉用，如 递归字符）。 */
    private final String label;

    /** 一句话说明（前端下拉分组描述用）。 */
    private final String desc;

    ChunkStrategy(String key, String label, String desc) {
        this.key = key;
        this.label = label;
        this.desc = desc;
    }

    public String getKey() {
        return key;
    }

    public String getLabel() {
        return label;
    }

    public String getDesc() {
        return desc;
    }

    /** 默认策略：递归字符切分（通用性最好）。 */
    public static ChunkStrategy defaultStrategy() {
        return RECURSIVE;
    }

    /** 按 key 解析策略（大小写不敏感）；null / 空 / 未知一律回退默认（宽容处理，避免脏数据中断入库）。 */
    public static ChunkStrategy fromKey(String key) {
        if (key != null) {
            for (ChunkStrategy s : values()) {
                if (s.key.equalsIgnoreCase(key.trim())) {
                    return s;
                }
            }
        }
        return defaultStrategy();
    }
}
