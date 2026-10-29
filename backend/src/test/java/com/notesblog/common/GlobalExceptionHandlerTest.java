package com.notesblog.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 异常收敛的边界：出错时返回的 HTTP 状态码要能区分「路径写错」和「程序崩了」。
 *
 * <p>直接调 handler 而不是起 MockMvc：这两条只关心映射本身，
 * 走切片反而会被静态资源解析的真实行为干扰，测出来不稳定。
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("未命中的路径返回 404，而不是 500")
    void noResourceIsNotFound() {
        var response = handler.handleNoResource(new NoResourceFoundException(HttpMethod.GET, "api/blog/stats"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo(404);
        assertThat(response.getBody().getMessage()).contains("接口不存在");
    }

    @Test
    @DisplayName("真异常仍然是 500")
    void realFailureStaysInternalError() {
        var response = handler.handleOther(new IllegalStateException("boom"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo(500);
        // 内部细节不能透给前端
        assertThat(response.getBody().getMessage()).doesNotContain("boom");
    }
}
