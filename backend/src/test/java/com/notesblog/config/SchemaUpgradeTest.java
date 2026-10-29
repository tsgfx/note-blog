package com.notesblog.config;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.jdbc.core.JdbcTemplate;

@ExtendWith(MockitoExtension.class)
class SchemaUpgradeTest {

    @Mock
    private JdbcTemplate jdbc;

    @InjectMocks
    private SchemaUpgrade schemaUpgrade;

    @Test
    @DisplayName("列与表均已存在时不做任何 ALTER 或 CREATE 操作")
    void whenEverythingExistsDoesNothing() {
        // mock tables and columns exist (count = 1)
        when(jdbc.queryForObject(anyString(), eq(Integer.class), anyString()))
                .thenReturn(1);
        when(jdbc.queryForObject(anyString(), eq(Integer.class), anyString(), anyString()))
                .thenReturn(1);

        schemaUpgrade.run(new DefaultApplicationArguments(new String[0]));

        verify(jdbc, never()).execute(anyString());
    }

    @Test
    @DisplayName("notes.view_count 缺失时自动执行 ALTER TABLE 添加列")
    void whenViewCountMissingAddsColumn() {
        // tables exist
        when(jdbc.queryForObject(anyString(), eq(Integer.class), anyString()))
                .thenReturn(1);
        // other columns exist, view_count missing
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq("users"), eq("token_version")))
                .thenReturn(1);
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq("notes"), eq("has_unpublished_edits")))
                .thenReturn(1);
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq("notes"), eq("view_count")))
                .thenReturn(0);

        schemaUpgrade.run(new DefaultApplicationArguments(new String[0]));

        verify(jdbc).execute("ALTER TABLE notes ADD COLUMN view_count BIGINT NOT NULL DEFAULT 0 AFTER has_unpublished_edits");
    }
}
