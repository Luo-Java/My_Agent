package org.luo.controller;

import lombok.extern.slf4j.Slf4j;
import org.luo.dto.ChatAttachment;
import org.luo.infrastructure.attachment.AttachmentService;
import org.luo.infrastructure.attachment.AttachmentStorageService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 对话附件处理接口：上传任意文件（图片 / 文档）→ AttachmentService 转成纯文本描述 + 原文件落盘。
 * <p>
 * 前端在用户点发送前把本轮所有附件一次性发给本端点，拿到每项 {@code type + content + storedName + size} 后，
 * 再随 {@code ChatRequest.attachments} 发到 {@code /api/chat/stream}。其中 {@code content}（解析文本）
 * 仅当轮注入 LLM 上下文；{@code storedName/size}（落盘元数据）写入消息的 attachments_json，仅服务历史回看。
 */
@Slf4j
@RestController
@RequestMapping("/api/chat/attachment")
public class AttachmentController {

    private final AttachmentService attachmentService;

    public AttachmentController(AttachmentService attachmentService) {
        this.attachmentService = attachmentService;
    }

    /**
     * 批量处理附件：每张 / 每个文件产生一个结果，顺序与入参一致。单文件解析失败不会让整体失败：
     * 该文件的结果替换为占位说明（{@code type="file"}），前端可继续把结果发到对话接口。
     *
     * @param files 上传文件（multipart/form-data，字段名 {@code files}，可多选）
     * @return {@code {"results":[{type,filename,content,storedName,size,url}, ...]}}，与入参顺序对齐
     */
    @PostMapping("/process")
    public Map<String, Object> process(@RequestParam("files") List<MultipartFile> files) {
        log.info("附件处理请求：文件数={}", files == null ? 0 : files.size());
        if (files == null || files.isEmpty()) {
            Map<String, Object> empty = new HashMap<>();
            empty.put("results", List.of());
            return empty;
        }
        List<ChatAttachment> atts = attachmentService.process(files);
        List<Map<String, Object>> results = new ArrayList<>(atts.size());
        for (ChatAttachment a : atts) {
            Map<String, Object> m = new HashMap<>(6);
            m.put("type", a.type() == null ? "file" : a.type());
            m.put("filename", a.filename() == null ? "" : a.filename());
            m.put("content", a.content() == null ? "" : a.content());
            // 落盘信息：前端原样回传，后端据此把元数据写入消息（供历史回看）
            m.put("storedName", a.storedName());
            m.put("size", a.size());
            m.put("url", AttachmentStorageService.url(a.storedName()));
            results.add(m);
        }
        Map<String, Object> result = new HashMap<>();
        result.put("results", results);
        return result;
    }
}
