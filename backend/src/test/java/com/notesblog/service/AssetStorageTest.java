package com.notesblog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.notesblog.common.NotFoundException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

/** 图片存储的路径约束与魔数校验。纯单元测试，用临时目录。 */
class AssetStorageTest {

    @TempDir
    Path root;

    private AssetStorage storage;

    @BeforeEach
    void setUp() {
        storage = new AssetStorage(root.toString());
    }

    @Test
    @DisplayName("拒绝 ../ 相对穿越")
    void readRejectsDotDotTraversal() {
        assertThatThrownBy(() -> storage.read("../../etc/passwd"))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("拒绝绝对路径")
    void readRejectsAbsolutePath() {
        assertThatThrownBy(() -> storage.read("/etc/passwd"))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("拒绝含空字节的路径")
    void readRejectsNullByte() {
        assertThatThrownBy(() -> storage.read("a\0b.png"))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("不存在的文件返回 404")
    void readMissingFile() {
        assertThatThrownBy(() -> storage.read("nope.png"))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("扩展名是 png 但内容不是图片：拒绝")
    void storeRejectsFakeMagicBytes() {
        MockMultipartFile fake = new MockMultipartFile("file", "a.png", "image/png",
                "not an image".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> storage.store(fake))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("格式不符");
    }

    @Test
    @DisplayName("不支持的扩展名：拒绝")
    void storeRejectsUnsupportedExtension() {
        MockMultipartFile bmp = new MockMultipartFile("file", "a.bmp", "image/bmp",
                new byte[] {'B', 'M', 0, 0});

        assertThatThrownBy(() -> storage.store(bmp))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("仅支持");
    }

    @Test
    @DisplayName("合法 PNG 落盘并返回相对路径")
    void storeAcceptsRealPng() throws Exception {
        // 1x1 透明 PNG
        byte[] png = java.util.Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");
        MockMultipartFile file = new MockMultipartFile("file", "dot.png", "image/png", png);

        String relative = storage.store(file);

        assertThat(relative).startsWith("uploads/").endsWith(".png");
        assertThat(Files.isRegularFile(root.resolve(relative))).isTrue();
    }
}
