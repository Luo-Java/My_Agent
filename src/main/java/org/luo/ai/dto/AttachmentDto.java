package org.luo.ai.dto;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import org.luo.ai.infrastructure.attachment.AttachmentStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 历史消息中的附件展示项（仅元数据，不含附件正文）。
 * <p>
 * 由 {@code chat_message.attachments_json} 解析而来，只用于历史记录渲染缩略图 / 下载链接；
 * 不参与记忆读取（DbChatMemory 只取 content），对 LLM 上下文与 token 零影响。
 *
 * @param type       附件类型：{@code image} / {@code text} / {@code file}
 * @param filename   原始文件名
 * @param storedName 落盘存储名（磁盘文件名）；为空表示原文件未落盘，仅能显示文件名
 * @param url        可直接访问的只读 URL（如 /files/xxx）；storedName 为空时为 null
 * @param size       原文件字节数；未知时为 null
 */
public record AttachmentDto(String type, String filename, String storedName, String url, Long size) {

    private static final Logger log = LoggerFactory.getLogger(AttachmentDto.class);

    /**
     * 解析 {@code chat_message.attachments_json}；空串或格式异常一律返回空列表 ——
     * 历史读取与会话导出都不应因一条脏数据整体失败。
     * <p>
     * 解析收在本记录里，与 {@link KbCitation#parse} 对称：这份 JSON 现有历史接口、会话导出两个消费方，
     * 各写一遍迟早会漂移。{@code url} 由 {@code storedName} <b>现算</b>而非落库 ——
     * 服务地址/端口一变，库里存下的旧 URL 就是错的。
     */
    public static List<AttachmentDto> parse(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            JSONArray arr = JSONUtil.parseArray(json);
            List<AttachmentDto> list = new ArrayList<>(arr.size());
            for (int i = 0; i < arr.size(); i++) {
                JSONObject j = arr.getJSONObject(i);
                String storedName = j.getStr("storedName");
                list.add(new AttachmentDto(
                        j.getStr("type"),
                        j.getStr("filename"),
                        storedName,
                        AttachmentStorageService.url(storedName),
                        j.getLong("size")));
            }
            return list;
        } catch (Exception e) {
            log.warn("解析附件元数据失败（按无附件处理）：{}", json, e);
            return List.of();
        }
    }
}
