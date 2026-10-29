package com.notesblog.dto;

import java.util.List;

/**
 * 目录树节点。
 *
 * @param name     目录名（最后一段）
 * @param path     完整相对路径，作为筛选参数回传给后端；「根目录」「未归档」是伪节点，path 与 name 相同
 * @param count    该目录及其子目录下的笔记总数
 * @param children 子目录，按篇数倒序
 */
public record FolderNodeDto(String name, String path, long count, List<FolderNodeDto> children) {

    public FolderNodeDto {
        children = children == null ? List.of() : List.copyOf(children);
    }

    public boolean leaf() {
        return children.isEmpty();
    }
}
