package com.notesblog.service;

import com.notesblog.domain.Note;
import com.notesblog.domain.NoteStatus;
import com.notesblog.domain.Tag;
import com.notesblog.dto.PreflightReport;
import com.notesblog.repository.NoteRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 发布前预检：把「发不出去」和「发出去可能后悔」的理由摆到人眼前。
 *
 * <p>为什么需要它：发布是全站唯一不可撤销的动作。原来的批量发布只弹一句
 * 「正文为空的会被跳过」，用户看不到自己选中的 200 篇里到底混了什么——
 * 可能是没写完的草稿，也可能是贴着 API Key 和内网地址的调试笔记。
 *
 * <p>规则刻意保守：只报「几乎肯定不该发」的形态，宁可漏报也不制造噪音。
 * 每条发现都能对应到具体文本，不猜意图。
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PublishPreflightService {

    /** 只扫前这么多字符：凭据和手机号几乎总在笔记开头，全篇扫只是浪费。 */
    private static final int SCAN_LIMIT = 20_000;

    /** 常见的密钥/令牌形态。命中不代表一定泄漏，但值得人看一眼。 */
    private static final Pattern SECRET = Pattern.compile(
            "sk-[A-Za-z0-9]{16,}" // OpenAI/硅基流动风格
                    + "|-----BEGIN [A-Z ]*PRIVATE KEY-----"
                    + "|(?i)(?:password|passwd|pwd)\\s*[:=]\\s*\\S{4,}"
                    + "|(?i)(?:api[_-]?key|access[_-]?token|secret)\\s*[:=]\\s*[\"']?[A-Za-z0-9_\\-]{16,}");

    /**
     * 个人隐私：手机号、身份证号。公开博客上出现这两样基本是失误。
     *
     * <p>身份证必须有合法的生日段（19xx/20xx + 01-12 月 + 01-31 日）才判定，
     * 否则 18 位的时间戳、订单号、哈希前 18 位全会被误报——警告一多就没人看了。
     */
    private static final Pattern PRIVATE_INFO = Pattern.compile(
            "(?<!\\d)1[3-9]\\d{9}(?!\\d)"
                    + "|(?<!\\d)[1-9]\\d{5}(?:19|20)\\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\\d|3[01])\\d{3}[\\dXx](?!\\d)");

    private final NoteRepository noteRepository;

    public PreflightReport check(List<Long> ids) {
        List<Long> distinct = ids.stream().filter(Objects::nonNull).distinct().toList();
        Map<Long, Note> found = noteRepository.findAllById(distinct).stream()
                .collect(Collectors.toMap(Note::getId, note -> note));

        List<PreflightReport.Item> items = new ArrayList<>(distinct.size());
        int blockers = 0;
        int warnings = 0;
        for (Long id : distinct) {
            Note note = found.get(id);
            if (note == null) {
                items.add(new PreflightReport.Item(id, "（已不存在）",
                        List.of(PreflightReport.Finding.block("MISSING", "笔记已不存在，会被跳过"))));
                blockers++;
                continue;
            }
            List<PreflightReport.Finding> findings = analyze(
                    note.getContentMd(), note.getSummary(),
                    note.getTags().stream().map(Tag::getName).toList(),
                    note.getStatus() == NoteStatus.PUBLISHED);
            items.add(new PreflightReport.Item(id, note.getTitle(), findings));
            for (PreflightReport.Finding finding : findings) {
                if (PreflightReport.Finding.BLOCK.equals(finding.level())) {
                    blockers++;
                } else {
                    warnings++;
                }
            }
        }
        return new PreflightReport(items, blockers, warnings);
    }

    /**
     * 单篇的规则判定。抽成静态方法是为了能脱离数据库直接测——
     * 这些正则的边界（手机号别误伤版本号、sk- 别误伤普通单词）只能靠用例钉住。
     */
    static List<PreflightReport.Finding> analyze(
            String contentMd, String summary, List<String> tags, boolean published) {
        List<PreflightReport.Finding> findings = new ArrayList<>(3);

        String content = contentMd == null ? "" : contentMd;
        if (content.isBlank()) {
            // 与 NoteService.changeStatus 的判断保持一致：正文为空发不出去
            findings.add(PreflightReport.Finding.block("EMPTY_CONTENT", "正文为空，发布时会被跳过"));
        }
        if (published) {
            findings.add(PreflightReport.Finding.warn("ALREADY_PUBLISHED", "已经是发布状态，会被跳过"));
        }

        String scanned = content.length() > SCAN_LIMIT ? content.substring(0, SCAN_LIMIT) : content;
        if (SECRET.matcher(scanned).find()) {
            findings.add(PreflightReport.Finding.warn("SECRET",
                    "正文疑似包含密钥/密码。公开后可能被爬走，建议先脱敏"));
        }
        if (PRIVATE_INFO.matcher(scanned).find()) {
            findings.add(PreflightReport.Finding.warn("PRIVATE_INFO",
                    "正文疑似包含手机号或身份证号，确认是否要公开"));
        }
        if (summary == null || summary.isBlank()) {
            findings.add(PreflightReport.Finding.warn("NO_SUMMARY", "没有摘要，博客列表会显得空"));
        }
        if (tags == null || tags.isEmpty()) {
            findings.add(PreflightReport.Finding.warn("NO_TAGS", "没有标签，读者无法顺着标签找到它"));
        }
        return findings;
    }
}
