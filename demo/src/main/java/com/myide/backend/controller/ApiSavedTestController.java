package com.myide.backend.controller;

import com.myide.backend.dto.SavedTestRequest;
import com.myide.backend.dto.SavedTestResponse;
import com.myide.backend.service.ApiSavedTestService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/test")
public class ApiSavedTestController {

    private final ApiSavedTestService service;

    public ApiSavedTestController(ApiSavedTestService service) {
        this.service = service;
    }

    @PostMapping
    public SavedTestResponse create(@RequestBody SavedTestRequest req) {
        return service.create(req);
    }

    @GetMapping
    public List<SavedTestResponse> list() {
        return service.list();
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }
}