package com.notesblog.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.notesblog.dto.FolderNodeDto;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 目录树聚合：父目录累计子树、根目录/未归档归位、排序稳定。
 *
 * <p>这块最容易出的错不是崩溃，是「树上写 12 篇、点进去 3 篇」——
 * 数字和列表对不上，用户会直接不信这个导航。
 */
class FolderTreeTest {

    @Test
    @DisplayName("多级路径逐段建树，父目录篇数 = 子树之和")
    void buildsNestedTree() {
        Map<String, Long> counts = Map.of(
                "c++/项目A/指针.md", 3L,
                "c++/项目A/数组.md", 1L,
                "c++/项目B/x.md", 2L,
                "科研/VLA.md", 1L);

        List<FolderNodeDto> tree = FolderTree.build(counts);

        assertThat(tree).extracting(FolderNodeDto::name).containsExactly("c++", "科研");
        FolderNodeDto cpp = tree.get(0);
        assertThat(cpp.path()).isEqualTo("c++");
        assertThat(cpp.count()).isEqualTo(6);
        // 子目录按篇数倒序：项目A(4) 在 项目B(2) 前面
        assertThat(cpp.children()).extracting(FolderNodeDto::name).containsExactly("项目A", "项目B");
        assertThat(cpp.children().get(0).path()).isEqualTo("c++/项目A");
        assertThat(cpp.children().get(0).count()).isEqualTo(4);
    }

    @Test
    @DisplayName("同名不同父的目录各自成节点，路径不串")
    void sameNameDifferentParent() {
        Map<String, Long> counts = Map.of(
                "a/assets/x.md", 1L,
                "b/assets/y.md", 2L);

        List<FolderNodeDto> tree = FolderTree.build(counts);

        assertThat(tree).extracting(FolderNodeDto::path).containsExactly("b", "a");
        assertThat(tree.get(0).children()).extracting(FolderNodeDto::path).containsExactly("b/assets");
        assertThat(tree.get(1).children()).extracting(FolderNodeDto::path).containsExactly("a/assets");
    }

    @Test
    @DisplayName("根目录与未归档是伪节点：无子节点，且固定排在真实目录之后")
    void pseudoNodesGoLast() {
        Map<String, Long> counts = new HashMap<>();
        counts.put("c++/项目A/指针.md", 1L);
        counts.put("单一文件.md", 5L); // 没有目录 → 根目录
        counts.put(null, 9L); // 没有 sourcePath → 未归档
        counts.put("  ", 1L); // 空白也算未归档

        List<FolderNodeDto> tree = FolderTree.build(counts);

        assertThat(tree).extracting(FolderNodeDto::name)
                .containsExactly("c++", NoteService.LIBRARY_ROOT, NoteService.LIBRARY_UNFILED);
        FolderNodeDto root = tree.get(1);
        assertThat(root.count()).isEqualTo(5);
        assertThat(root.children()).isEmpty();
        assertThat(root.leaf()).isTrue();
        // 未归档把 null 和空白合并计数
        assertThat(tree.get(2).count()).isEqualTo(10);
    }

    @Test
    @DisplayName("深路径逐层展开，每层都带累计篇数")
    void deepPath() {
        Map<String, Long> counts = Map.of("a/b/c/d/e.md", 1L, "a/b/c/f.md", 2L);

        List<FolderNodeDto> tree = FolderTree.build(counts);

        // a / b / c 三层都包含两篇，最深的 d 只有 e.md 一篇
        List<String> paths = new java.util.ArrayList<>();
        List<Long> countsAlongPath = new java.util.ArrayList<>();
        FolderNodeDto node = tree.get(0);
        while (true) {
            paths.add(node.path());
            countsAlongPath.add(node.count());
            if (node.children().isEmpty()) break;
            node = node.children().get(0);
        }
        assertThat(paths).containsExactly("a", "a/b", "a/b/c", "a/b/c/d");
        assertThat(countsAlongPath).containsExactly(3L, 3L, 3L, 1L);
    }

    @Test
    @DisplayName("sourcePath 折算目录：去掉文件名，忽略开头的斜杠")
    void foldersOf() {
        assertThat(FolderTree.foldersOf(null)).isEmpty();
        assertThat(FolderTree.foldersOf("")).isEmpty();
        assertThat(FolderTree.foldersOf("单一文件.md")).isEmpty();
        assertThat(FolderTree.foldersOf("/单一文件.md")).isEmpty();
        assertThat(FolderTree.foldersOf("c++/指针.md")).containsExactly("c++");
        assertThat(FolderTree.foldersOf("c++/项目A/指针.md")).containsExactly("c++", "项目A");
        assertThat(FolderTree.foldersOf("/c++/项目A/指针.md")).containsExactly("c++", "项目A");
    }

    @Test
    @DisplayName("空输入不炸")
    void emptyInput() {
        assertThat(FolderTree.build(Map.of())).isEmpty();
    }
}
