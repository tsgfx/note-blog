package com.notesblog.service;

import com.notesblog.common.NotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/** 图片存储：迁移图片与新上传图片共用一个根目录，所有路径都限制在根目录内。 */
@Slf4j
@Service
public class AssetStorage {

    private static final long MAX_UPLOAD_BYTES = 10L * 1024 * 1024;
    private static final Map<String, String> MIME = Map.of(
            "png", "image/png",
            "jpg", "image/jpeg",
            "jpeg", "image/jpeg",
            "gif", "image/gif",
            "webp", "image/webp");

    private final Path root;

    public AssetStorage(@Value("${assets.root}") String root) {
        this.root = Paths.get(root).toAbsolutePath().normalize();
    }

    /** 读取已存在的图片；路径越界或不存在统一返回 404。 */
    public Path read(String relativePath) {
        Path target = confine(relativePath);
        if (!Files.isRegularFile(target)) {
            throw new NotFoundException("图片不存在");
        }
        return target;
    }

    /** 检查相对路径的图片文件是否存在 */
    public boolean exists(String relativePath) {
        try {
            Path target = confine(relativePath);
            return Files.isRegularFile(target);
        } catch (Exception e) {
            return false;
        }
    }

    public static String contentType(Path file) {
        return MIME.getOrDefault(extension(file.getFileName().toString()), "application/octet-stream");
    }

    /** 保存上传图片，返回相对根目录的路径，如 uploads/2026/uuid.png。 */
    public String store(MultipartFile file) throws IOException {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("文件不能为空");
        }
        if (file.getSize() > MAX_UPLOAD_BYTES) {
            throw new IllegalArgumentException("图片不能超过 10MB");
        }
        String ext = extension(file.getOriginalFilename() == null ? "" : file.getOriginalFilename());
        if (!MIME.containsKey(ext)) {
            throw new IllegalArgumentException("仅支持 png、jpg、gif、webp 图片");
        }
        if (!matchesSignature(ext, head(file))) {
            throw new IllegalArgumentException("文件内容与图片格式不符");
        }
        String relative = "uploads/" + LocalDate.now().getYear() + "/" + UUID.randomUUID() + "." + ext;
        Path target = confine(relative);
        Files.createDirectories(target.getParent());
        file.transferTo(target);
        log.info("asset_uploaded path={} bytes={}", relative, file.getSize());
        return relative;
    }

    private Path confine(String relativePath) {
        if (relativePath == null || relativePath.isBlank() || relativePath.indexOf('\0') >= 0) {
            throw new NotFoundException("图片不存在");
        }
        Path target = root.resolve(relativePath).normalize();
        if (!target.startsWith(root)) {
            throw new NotFoundException("图片不存在");
        }
        return target;
    }

    /** 读文件头做魔数校验；流必须关闭，否则每次上传泄漏一个句柄。 */
    private static byte[] head(MultipartFile file) throws IOException {
        try (InputStream in = file.getInputStream()) {
            return in.readNBytes(12);
        }
    }

    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static boolean matchesSignature(String ext, byte[] head) {
        return switch (ext) {
            case "png" -> head.length >= 4 && (head[0] & 0xFF) == 0x89
                    && head[1] == 'P' && head[2] == 'N' && head[3] == 'G';
            case "jpg", "jpeg" -> head.length >= 3 && (head[0] & 0xFF) == 0xFF
                    && (head[1] & 0xFF) == 0xD8 && (head[2] & 0xFF) == 0xFF;
            case "gif" -> head.length >= 4 && head[0] == 'G' && head[1] == 'I'
                    && head[2] == 'F' && head[3] == '8';
            case "webp" -> head.length >= 12 && head[0] == 'R' && head[1] == 'I'
                    && head[2] == 'F' && head[3] == 'F' && head[8] == 'W'
                    && head[9] == 'E' && head[10] == 'B' && head[11] == 'P';
            default -> false;
        };
    }
}
