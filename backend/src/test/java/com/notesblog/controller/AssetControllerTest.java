package com.notesblog.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.notesblog.service.AssetStorage;
import com.notesblog.service.AuditService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 图片接口的业务错误码。安全链在这里被关掉（addFilters = false），
 * 401 由 SecurityConfigTest 统一覆盖，避免每个用例都造令牌。
 */
@WebMvcTest(controllers = AssetController.class)
@AutoConfigureMockMvc(addFilters = false)
@TestPropertySource(properties = "assets.root=target/test-assets")
@org.springframework.context.annotation.Import(AssetStorage.class)
class AssetControllerTest {

    private static final Path ROOT = Path.of("target/test-assets");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuditService auditService;
    // 切片会扫描到 JwtAuthFilter 与主启动类的 seedAdmin，即使关了过滤器也要满足它们的依赖
    @MockitoBean
    private com.notesblog.service.JwtService jwtService;
    @MockitoBean
    private com.notesblog.service.TokenVersionService tokenVersionService;
    @MockitoBean
    private com.notesblog.service.AuthService authService;
    @MockitoBean
    private com.notesblog.service.PublishedAssetService publishedAssetService;

    @BeforeEach
    void prepareRoot() throws Exception {
        Files.createDirectories(ROOT);
        org.mockito.Mockito.when(publishedAssetService.isPubliclyReadable(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(false);
    }

    @Test
    @DisplayName("扩展名是 png 但内容不是图片：400")
    void fakePngIsRejected() throws Exception {
        MockMultipartFile fake = new MockMultipartFile("file", "a.png", "image/png",
                "not an image".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/api/assets").file(fake))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("与图片格式不符")));
    }

    @Test
    @DisplayName("不支持的扩展名：400")
    void unsupportedExtensionIsRejected() throws Exception {
        MockMultipartFile bmp = new MockMultipartFile("file", "a.bmp", "image/bmp",
                new byte[] {'B', 'M', 0, 0});

        mockMvc.perform(multipart("/api/assets").file(bmp))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("仅支持")));
    }

    @Test
    @DisplayName("路径穿越：404 而不是把文件读出去")
    void pathTraversalIsRejected() throws Exception {
        mockMvc.perform(get("/api/assets/%2e%2e%2f%2e%2e%2fsecret.png"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("不存在的图片：404")
    void missingAssetReturns404() throws Exception {
        mockMvc.perform(get("/api/assets/nope.png"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("匿名 + 文件存在但不在已发布白名单：404（不区分\"不存在\"和\"不公开\"）")
    void anonymousCannotReadUnpublishedAsset() throws Exception {
        Files.write(ROOT.resolve("private.png"), new byte[] {(byte) 0x89, 'P', 'N', 'G', 1, 2, 3, 4});

        mockMvc.perform(get("/api/assets/private.png"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("匿名 + 已发布笔记引用的图片：200")
    void anonymousCanReadPublishedAsset() throws Exception {
        Files.write(ROOT.resolve("published.png"), new byte[] {(byte) 0x89, 'P', 'N', 'G', 1, 2, 3, 4});
        org.mockito.Mockito.when(publishedAssetService.isPubliclyReadable("published.png")).thenReturn(true);

        mockMvc.perform(get("/api/assets/published.png"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("登录用户不受白名单限制：草稿图也能读（编辑预览需要）")
    void signedInUserReadsAnyAsset() throws Exception {
        Files.write(ROOT.resolve("draft-only.png"), new byte[] {(byte) 0x89, 'P', 'N', 'G', 1, 2, 3, 4});

        mockMvc.perform(get("/api/assets/draft-only.png")
                        // addFilters=false 时 user() 只进 session 属性，请求上看不到；
                        // 直接设置 principal，模拟 SecurityContext 包装后的效果
                        .with(request -> {
                            request.setUserPrincipal(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                                    "admin", null, java.util.List.of()));
                            return request;
                        }))
                .andExpect(status().isOk());
    }
}
