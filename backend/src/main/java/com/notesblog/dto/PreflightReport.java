package com.notesblog.dto;

import java.util.List;

/**
 * 批量发布前的预检报告。
 *
 * <p>发布是**唯一不可撤销的动作**（下线只是从列表移除，被爬虫抓走就晚了），
 * 所以真正执行前先把「会被跳过」和「发出去有风险」两类情况摊开给人看。
 *
 * @param blockers 阻断项总数：有阻断就说明这批里有的根本发不出去，先处理再发
 * @param warnings 警告总数：能发，但可能不想这么发（含凭据、没摘要、没标签…）
 */
public record PreflightReport(List<Item> items, int blockers, int warnings) {

    public record Item(Long id, String title, List<Finding> findings) {
        public boolean blocked() {
            return findings.stream().anyMatch(f -> Finding.BLOCK.equals(f.level()));
        }
    }

    /**
     * @param level BLOCK = 发不出去 / WARN = 发得出去但有风险
     * @param code  机器可读的原因，前端据此决定图标和措辞
     */
    public record Finding(String level, String code, String message) {
        public static final String BLOCK = "BLOCK";
        public static final String WARN = "WARN";

        public static Finding block(String code, String message) {
            return new Finding(BLOCK, code, message);
        }

        public static Finding warn(String code, String message) {
            return new Finding(WARN, code, message);
        }
    }
}
