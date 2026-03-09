package com.myide.backend.controller.apitest;

import com.myide.backend.dto.apitest.SavedTestRequest;
import com.myide.backend.dto.apitest.SavedTestResponse;
import com.myide.backend.service.apitest.ApiSavedTestService;
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