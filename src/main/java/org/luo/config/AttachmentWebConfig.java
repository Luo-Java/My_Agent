package org.luo.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 附件静态访问映射：把 {@code /files/**} 映射到附件落盘目录（{@code app.attachment.dir}），
 * 让历史记录中的图片缩略图与文档下载链接可直接访问。
 * <p>
 * 刻意<b>不使用 {@code /api} 前缀</b>：{@link ApiSecurityConfig} 对 {@code /api/**} 强制校验
 * X-Api-Key，而浏览器 {@code <img src>} / 直链下载无法附带自定义头，走 {@code /api} 会 401。
 * <p>
 * 本映射是只读静态资源，仅为本地/内网回看服务。由于它<b>免鉴权</b>、且浏览器按文件后缀推断
 * Content-Type 决定「渲染」还是「下载」，落盘后缀已由
 * {@link org.luo.infrastructure.attachment.AttachmentStorageService} 白名单化
 * （非图片/文档后缀统一落成 {@code .bin} = application/octet-stream），
 * 避免上传 {@code .html}/{@code .svg} 后被同源渲染成页面或执行脚本。
 * 若需更强隔离，可另加网关鉴权。
 */
@Slf4j
@Configuration
public class AttachmentWebConfig implements WebMvcConfigurer {

    private final Path baseDir;

    public AttachmentWebConfig(@Value("${app.attachment.dir:./data/attachments}") String dir) {
        this.baseDir = Paths.get(dir).toAbsolutePath().normalize();
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String location = baseDir.toUri().toString();
        if (!location.endsWith("/")) {
            location += "/";
        }
        registry.addResourceHandler("/files/**").addResourceLocations(location);
        log.debug("附件静态映射：/files/** -> {}", location);
    }
}
