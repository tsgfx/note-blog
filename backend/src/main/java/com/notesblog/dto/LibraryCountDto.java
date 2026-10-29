package com.notesblog.dto;

/** 左侧"库"导航的一项：库名与篇数。 */
public record LibraryCountDto(String name, long count) {
}
