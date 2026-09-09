package org.luo.controller;

import lombok.extern.slf4j.Slf4j;
import org.luo.service.VisionService;
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
 * 多模态视觉识别接口：上传图片 → VisionService 识别为文本描述 caption。
 * <p>
 * 前端在用户点发送前先调用本端点拿到 caption，再把 caption 拼到 ChatRequest.attachments
 * 一起发到 {@code /api/chat/stream}。本端点不做流式、不进会话记忆——caption 仅当轮注入，
 * 原始二进制不进数据库，刷新/重开会话后不再出现（保持对话存储的轻量）。
 */
@Slf4j
@RestController
@RequestMapping("/api/chat/vision")
public class VisionController {

    private final VisionService visionService;

    public VisionController(VisionService visionService) {
        this.visionService = visionService;
    }

    /**
     * 批量识别图片：每张图产生一个 caption，顺序与入参一致。
     * <p>
     * 单图识别失败不会让整体失败：caption 会被替换为占位文本（如「[图片识别失败：...]」），
     * 前端可继续把 caption 列表发到对话接口，由 LLM 决定如何处理。
     *
     * @param files 上传的图片文件（multipart/form-data，字段名 {@code files}，可多选）
     * @return {@code {"captions":[...], "filenames":[...]}} 两个数组按顺序对齐
     */
    @PostMapping("/describe")
    public Map<String, Object> describe(@RequestParam("files") List<MultipartFile> files) {
        log.info("视觉识别请求：文件数={}", files == null ? 0 : files.size());
        if (files == null || files.isEmpty()) {
            Map<String, Object> empty = new HashMap<>();
            empty.put("captions", List.of());
            empty.put("filenames", List.of());
            return empty;
        }
        List<String> filenames = new ArrayList<>(files.size());
        for (MultipartFile f : files) {
            String name = f.getOriginalFilename();
            filenames.add(name == null ? "image" : name);
        }
        List<String> captions = visionService.describeAll(files);
        Map<String, Object> result = new HashMap<>();
        result.put("captions", captions);
        result.put("filenames", filenames);
        return result;
    }
}