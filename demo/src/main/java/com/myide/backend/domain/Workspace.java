package com.myide.backend.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Workspace {
    @Id
    private String uuid;      // 실제 폴더명 (550e-8400...)
    private String name;      // 보여줄 이름 (쇼핑몰 프로젝트)
    private String ownerId;   // 방장 ID (user1)
    private String description;
}