package com.myide.backend.controller;



import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.util.UUID;

@RestController
@RequestMapping("/api/files")
public class FileController {

    // 💡 파일을 저장할 로컬 폴더 경로 (프로젝트 최상단에 /uploads 폴더가 생성됩니다)
    private final String uploadDir = System.getProperty("user.dir") + "/uploads/";

    @PostMapping("/upload")
    public ResponseEntity<String> uploadFile(@RequestParam("file") MultipartFile file) {
        try {
            // 폴더가 없으면 자동으로 생성
            File directory = new File(uploadDir);
            if (!directory.exists()) {
                directory.mkdirs();
            }

            // 원본 파일명에 겹치지 않는 랜덤 이름(UUID)을 붙여서 저장
            // (예: my_picture.png -> 3f2a1..._my_picture.png)
            String fileName = UUID.randomUUID() + "_" + file.getOriginalFilename();
            File dest = new File(uploadDir + fileName);
            file.transferTo(dest); // 🚀 서버 하드디스크에 파일 저장 완료!

            // 프론트엔드가 이미지를 볼 수 있는 실제 URL 반환
            String fileUrl = "http://localhost:8080/uploads/" + fileName;
            return ResponseEntity.ok(fileUrl);

        } catch (IOException e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError().build();
        }
    }
}
