package com.myide.backend.controller;



import org.springframework.beans.factory.annotation.Value;
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

    /*
     * 프론트에 돌려줄 주소의 앞부분(프로토콜 + 도메인).
     *
     * 예전에는 "http://localhost:8080" 이 그대로 박혀 있었다. 그러면 배포 환경에서
     * 프론트가 받는 주소가 접속자 본인의 PC를 가리키게 되어 올린 이미지가 전부 깨진다.
     * 프론트가 https 로 열려 있으면 http 주소라 브라우저가 아예 차단하기도 한다.
     *
     * 배포 서버에서는 APP_PUBLIC_BASE_URL 로 실제 도메인을 넣어 준다.
     */
    @Value("${app.public-base-url}")
    private String publicBaseUrl;

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
            // (설정값 끝에 / 가 붙어 있어도 //uploads 가 되지 않도록 떼어 낸다)
            String fileUrl = publicBaseUrl.replaceAll("/+$", "") + "/uploads/" + fileName;
            return ResponseEntity.ok(fileUrl);

        } catch (IOException e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError().build();
        }
    }
}
