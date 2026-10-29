package com.notesblog.dto;

/**
 * 标签及其笔记数，用于「按分类浏览」的左栏。
 *
 * @param name  标签名
 * @param count 该标签下的笔记数（只统计当前筛选范围内的，比如某个库）
 */
public record NoteTagCountDto(String name, long count) {
}
