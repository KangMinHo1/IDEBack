package com.myide.backend.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/system")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class SystemController {

    // 1. 시스템의 루트 드라이브 목록 조회 (C:\, D:\ 등)
    @GetMapping("/roots")
    public ResponseEntity<List<String>> getSystemRoots() {
        File[] roots = File.listRoots();
        List<String> rootPaths = Arrays.stream(roots)
                .map(File::getAbsolutePath)
                .collect(Collectors.toList());
        return ResponseEntity.ok(rootPaths);
    }

    // 2. 특정 경로의 하위 폴더 목록 조회
    @GetMapping("/folders")
    public ResponseEntity<List<Map<String, String>>> getSubFolders(@RequestParam String path) {
        File directory = new File(path);
        if (!directory.exists() || !directory.isDirectory()) {
            return ResponseEntity.badRequest().build();
        }

        File[] files = directory.listFiles(File::isDirectory); // 폴더만 필터링
        if (files == null) return ResponseEntity.ok(Collections.emptyList());

        List<Map<String, String>> folders = Arrays.stream(files)
                .map(f -> {
                    Map<String, String> map = new HashMap<>();
                    map.put("name", f.getName());
                    map.put("path", f.getAbsolutePath());
                    return map;
                })
                .sorted(Comparator.comparing(m -> m.get("name")))
                .collect(Collectors.toList());

        return ResponseEntity.ok(folders);
    }
}