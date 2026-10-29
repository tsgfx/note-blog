package com.notesblog.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.notesblog.dto.PreflightReport;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 发布前预检的规则边界。
 *
 * <p>这些正则最怕两种错：漏报（贴了 Key 还说没问题）和误报（把版本号当手机号，
 * 警告一多就没人看）。所以每条用例都盯着一个具体的边界，不放「大概能过」的断言。
 */
class PublishPreflightServiceTest {

    private static List<String> codes(List<PreflightReport.Finding> findings) {
        return findings.stream().map(PreflightReport.Finding::code).toList();
    }

    @Test
    @DisplayName("正文为空 → 阻断（与 changeStatus 的跳过规则一致）")
    void blankContentBlocks() {
        var findings = PublishPreflightService.analyze("   ", "摘要", List.of("tag"), false);

        assertThat(codes(findings)).contains("EMPTY_CONTENT");
        assertThat(findings).anyMatch(f -> PreflightReport.Finding.BLOCK.equals(f.level()));
    }

    @Test
    @DisplayName("内容干净 + 有摘要有标签 → 一条警告都没有")
    void cleanNoteHasNoFindings() {
        var findings = PublishPreflightService.analyze(
                "正常的技术笔记正文，讲 JVM 垃圾回收。", "一句话摘要", List.of("JVM"), false);

        assertThat(findings).isEmpty();
    }

    @Test
    @DisplayName("sk- 开头的密钥 → 警告（含凭据）")
    void detectsApiKey() {
        assertThat(codes(PublishPreflightService.analyze(
                "调用示例：api_key = sk-abcdefghijklmnop123456", "摘要", List.of("t"), false)))
                .contains("SECRET");
    }

    @Test
    @DisplayName("password: / token= 形态与私钥头也能识别")
    void detectsPasswordAndPrivateKey() {
        assertThat(codes(PublishPreflightService.analyze(
                "password: hunter2!", "摘要", List.of("t"), false))).contains("SECRET");
        assertThat(codes(PublishPreflightService.analyze(
                "access_token = \"abcdefghijklmnopqrst\"", "摘要", List.of("t"), false)))
                .contains("SECRET");
        assertThat(codes(PublishPreflightService.analyze(
                "-----BEGIN RSA PRIVATE KEY-----\nxxx", "摘要", List.of("t"), false)))
                .contains("SECRET");
    }

    @Test
    @DisplayName("手机号 / 身份证号 → 警告（隐私）")
    void detectsPrivateInfo() {
        assertThat(codes(PublishPreflightService.analyze(
                "联系我 13812345678 就行", "摘要", List.of("t"), false))).contains("PRIVATE_INFO");
        assertThat(codes(PublishPreflightService.analyze(
                "身份证 110101199003071234", "摘要", List.of("t"), false))).contains("PRIVATE_INFO");
    }

    @Test
    @DisplayName("版本号、IP、时间戳、17 位数字不能被当成手机号/身份证")
    void doesNotFlagTechnicalNumbers() {
        List<String> samples = List.of(
                "CUDA 12.8 + PyTorch 2.4.0 环境",
                "容器网关是 192.168.5.2，走 127.0.0.1:7897 代理",
                "时间戳 20261026203015123",
                "提交 8f3a9c2b1d4e5f6a7b8c9d0e1f2a3b4c5d6e7f80",
                "端口 18099、8317、5173 都试过");
        for (String sample : samples) {
            assertThat(codes(PublishPreflightService.analyze(sample, "摘要", List.of("t"), false)))
                    .as("不该被误报：%s", sample)
                    .doesNotContain("PRIVATE_INFO", "SECRET");
        }
    }

    @Test
    @DisplayName("没摘要 / 没标签 → 两条体验类警告，不阻断")
    void warnsAboutMissingMeta() {
        var findings = PublishPreflightService.analyze("有正文", null, List.of(), false);

        assertThat(codes(findings)).containsExactlyInAnyOrder("NO_SUMMARY", "NO_TAGS");
        assertThat(findings).noneMatch(f -> PreflightReport.Finding.BLOCK.equals(f.level()));
    }

    @Test
    @DisplayName("已是发布状态 → 提示会被跳过，但不阻断其他篇")
    void alreadyPublishedIsWarnOnly() {
        var findings = PublishPreflightService.analyze("正文", "摘要", List.of("t"), true);

        assertThat(codes(findings)).containsExactly("ALREADY_PUBLISHED");
        assertThat(findings.get(0).level()).isEqualTo(PreflightReport.Finding.WARN);
    }

    @Test
    @DisplayName("超长正文只扫前 2 万字符，后面的内容不参与判定")
    void onlyScansHeadOfContent() {
        String filler = "正常内容。".repeat(5000); // 25000 字符，超过扫描上限
        assertThat(codes(PublishPreflightService.analyze(
                filler + " password: leaked123", "摘要", List.of("t"), false)))
                .doesNotContain("SECRET");
    }
}
