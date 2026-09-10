package com.myide.backend.dto.system;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;

/*
 * 폴더 선택 창의 "새 폴더" 요청.
 *
 * path 는 화면에 보이는 가상 경로(C:\...)다. 진짜 경로로 바꾸는 일은
 * UserSpaceService 가 맡으므로 여기서는 문자열 그대로 받는다.
 */
@Getter
@NoArgsConstructor
public class CreateFolderRequest {

    @NotBlank(message = "만들 위치가 필요합니다.")
    private String path;

    @NotBlank(message = "폴더 이름은 필수입니다.")
    private String name;
}
