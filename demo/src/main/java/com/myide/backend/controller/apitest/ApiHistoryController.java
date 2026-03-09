package com.myide.backend.controller.apitest;

import com.myide.backend.dto.apitest.HistoryRequest;
import com.myide.backend.dto.apitest.HistoryResponse;
import com.myide.backend.service.apitest.ApiHistoryService;
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