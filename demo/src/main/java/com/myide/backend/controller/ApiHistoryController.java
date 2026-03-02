package com.myide.backend.controller;

import com.myide.backend.dto.HistoryRequest;
import com.myide.backend.dto.HistoryResponse;
import com.myide.backend.service.ApiHistoryService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/history")
public class ApiHistoryController {

    private final ApiHistoryService service;

    public ApiHistoryController(ApiHistoryService service) {
        this.service = service;
    }

    @PostMapping
    public HistoryResponse create(@RequestBody HistoryRequest req) {
        return service.create(req);
    }

    @GetMapping
    public List<HistoryResponse> list(@RequestParam(defaultValue = "10") int limit) {
        return service.list(limit);
    }
}