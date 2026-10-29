package com.notesblog.service;

import com.notesblog.dto.FolderNodeDto;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把「sourcePath → 篇数」聚合成多级目录树。
 *
 * <p>纯函数，不碰数据库也不依赖 Spring：折算规则（哪一段算目录、根目录与未归档怎么归）
 * 只写在这一处，前端只负责渲染与展开，两边不会各算一套。
 *
 * <p>为什么不在 SQL 里递归：MySQL 8 的 {@code WITH RECURSIVE} 要先把路径拆成行，
 * 还得处理中文与大小写，SQL 会又长又难测；695 篇的规模在 Java 里聚合是毫秒级。
 * 真到十万篇再换物化路径表。
 */
public final class FolderTree {

    private FolderTree() {
    }

    private static final Comparator<FolderNodeDto> BY_COUNT_DESC =
            Comparator.comparingLong(FolderNodeDto::count).reversed().thenComparing(FolderNodeDto::name);

    /**
     * @param sourcePathCounts key 是笔记的完整 sourcePath（含文件名），value 是篇数
     * @return 顶层目录列表；「根目录」「未归档」固定排在真实目录之后
     */
    public static List<FolderNodeDto> build(Map<String, Long> sourcePathCounts) {
        Node virtualRoot = new Node("", "");
        Map<String, Long> pseudo = new LinkedHashMap<>();

        for (Map.Entry<String, Long> entry : sourcePathCounts.entrySet()) {
            String sourcePath = entry.getKey();
            long count = entry.getValue() == null ? 0L : entry.getValue();

            List<String> folders = foldersOf(sourcePath);
            if (folders.isEmpty()) {
                // 没有目录层级：null → 未归档，有文件名 → 根目录
                String bucket = (sourcePath == null || sourcePath.isBlank())
                        ? NoteService.LIBRARY_UNFILED
                        : NoteService.LIBRARY_ROOT;
                pseudo.merge(bucket, count, Long::sum);
                continue;
            }

            Node cursor = virtualRoot;
            StringBuilder path = new StringBuilder();
            for (String folder : folders) {
                if (path.length() > 0) path.append('/');
                path.append(folder);
                String fullPath = path.toString();
                cursor = cursor.children.computeIfAbsent(folder, name -> new Node(name, fullPath));
                // 父目录累计整棵子树：点 c++ 时应该看到它下面所有子目录的篇数
                cursor.count += count;
            }
        }

        List<FolderNodeDto> result = new ArrayList<>();
        for (Node child : virtualRoot.children.values()) {
            result.add(child.toDto());
        }
        result.sort(BY_COUNT_DESC);

        // 伪节点不是真实目录，按固定顺序挂在最后，避免跟真实目录抢排序
        for (String bucket : List.of(NoteService.LIBRARY_ROOT, NoteService.LIBRARY_UNFILED)) {
            Long count = pseudo.get(bucket);
            if (count != null) {
                result.add(new FolderNodeDto(bucket, bucket, count, List.of()));
            }
        }
        return result;
    }

    /**
     * 取出 sourcePath 的目录部分（去掉文件名）。
     *
     * <p>「c++/项目A/指针.md」→ [c++ 项目A]；「单一文件.md」→ []；
     * 开头的斜杠会被忽略（与 {@link NoteService#libraryOf} 的判断保持一致）。
     */
    static List<String> foldersOf(String sourcePath) {
        if (sourcePath == null || sourcePath.isBlank()) return List.of();
        String trimmed = sourcePath;
        while (trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1);
        }
        int slash = trimmed.lastIndexOf('/');
        if (slash < 0) return List.of();
        String dir = trimmed.substring(0, slash);
        if (dir.isBlank()) return List.of();

        List<String> segments = new ArrayList<>();
        for (String segment : dir.split("/")) {
            if (!segment.isBlank()) segments.add(segment);
        }
        return segments;
    }

    /** 建树期间的可变节点，最后一次性转成不可变 DTO */
    private static final class Node {
        final String name;
        final String path;
        long count;
        final Map<String, Node> children = new LinkedHashMap<>();

        Node(String name, String path) {
            this.name = name;
            this.path = path;
        }

        FolderNodeDto toDto() {
            List<FolderNodeDto> kids = new ArrayList<>();
            for (Node child : children.values()) {
                kids.add(child.toDto());
            }
            kids.sort(BY_COUNT_DESC);
            return new FolderNodeDto(name, path, count, kids);
        }
    }
}
