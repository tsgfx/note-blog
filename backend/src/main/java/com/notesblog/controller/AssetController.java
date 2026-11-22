package com.notesblog.controller;

import com.notesblog.common.ApiResponse;
import com.notesblog.common.NotFoundException;
import com.notesblog.service.AssetStorage;
import com.notesblog.service.AuditService;
import com.notesblog.service.PublishedAssetService;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.util.UriUtils;

@RestController
@RequestMapping("/api/assets")
@RequiredArgsConstructor
public class AssetController {

    private static final String PREFIX = "/api/assets/";
    private final AssetStorage storage;
    private final AuditService auditService;
    private final PublishedAssetService publishedAssets;

    /**
     * 图片读取。匿名用户只能读「已发布笔记引用的图片」——迁移图片的路径是
     * Obsidian 相对路径，可预测性高，不拦的话未公开笔记的截图等于公开；
     * 登录用户（编辑预览需要看草稿图）不受限。
     */
    @GetMapping("/**")
    public ResponseEntity<FileSystemResource> read(HttpServletRequest request, Authentication authentication) {
        String raw = request.getRequestURI().substring(PREFIX.length());
        String relative = UriUtils.decode(raw, StandardCharsets.UTF_8);
        if (!isAuthenticated(authentication) && !publishedAssets.isPubliclyReadable(relative)) {
            // 与「文件不存在」同一响应，不区分「存在但不公开」，避免暴露路径枚举结果
            throw new NotFoundException("图片不存在");
        }
        Path file = storage.read(relative);
        // 缓存 1 天而不是 7 天：图片路径是「标题式」的稳定路径，同名覆盖上传（换配图）
        // 很常见，7 天里读者会一直看到旧图。ETag 用「大小 + 修改时间」，
        // 浏览器过期后再来一次 304，既省流量又能及时换图。
        String etag = "\"" + file.toFile().length() + "-" + file.toFile().lastModified() + "\"";
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(AssetStorage.contentType(file)))
                .cacheControl(CacheControl.maxAge(Duration.ofDays(1)).cachePublic())
                .eTag(etag)
                .body(new FileSystemResource(file));
    }

    /** 管理员上传图片，返回可直接写入 Markdown 的地址。 */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<Map<String, String>> upload(@RequestParam("file") MultipartFile file)
            throws IOException {
        String relative = storage.store(file);
        auditService.record("ASSET_UPLOAD", relative, file.getOriginalFilename());
        return ApiResponse.ok(Map.of("url", PREFIX + relative));
    }

    private static boolean isAuthenticated(Authentication authentication) {
        return authentication != null && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);
    }
}
