package com.admtechhub.maestrohr.search;

import com.admtechhub.maestrohr.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/search")
@RequiredArgsConstructor
public class SearchController {

    private final SearchService searchService;

    @GetMapping
    public ResponseEntity<ApiResponse<SearchService.SearchResponse>> search(@RequestParam String q) {
        return ResponseEntity.ok(ApiResponse.success("Search results", searchService.search(q)));
    }
}
