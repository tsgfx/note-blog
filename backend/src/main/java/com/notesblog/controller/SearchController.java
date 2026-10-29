package com.notesblog.controller;

import com.notesblog.common.ApiResponse;
import com.notesblog.dto.SearchRequest;
import com.notesblog.dto.SearchResultDto;
import com.notesblog.service.SemanticSearchService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/search")
@RequiredArgsConstructor
public class SearchController {

    private final SemanticSearchService searchService;

    @PostMapping
    public ApiResponse<List<SearchResultDto>> search(@Valid @RequestBody SearchRequest request) {
        return ApiResponse.ok(searchService.search(request.question(), request.safeLimit()));
    }
}
