package com.myide.backend.controller.apitest;

import com.myide.backend.dto.apitest.ProxyRequest;
import com.myide.backend.dto.apitest.ProxyResponse;
import com.myide.backend.service.apitest.ProxyService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class ProxyController {

    private final ProxyService proxyService;

    public ProxyController(ProxyService proxyService) {
        this.proxyService = proxyService;
    }

    @PostMapping("/proxy")
    public ProxyResponse proxy(@Valid @RequestBody ProxyRequest req) {
        return proxyService.forward(req);
    }
}