package com.myide.backend.dto.ide;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FileRequest {

    @NotBlank(message = "워크스페이스 ID는 필수입니다.")
    private String workspaceId;

    @NotBlank(message = "프로젝트 이름은 필수입니다.")
    private String projectName;

    /*
     * 현재 작업 대상 브랜치명입니다.
     *
     * 예:
     * - master
     * - develop
     * - feature/login
     * - hotfix/security
     */
    private String branchName;

    /*
     * 새 브랜치를 만들 때 기준이 되는 브랜치입니다.
     *
     * Sourcetree식 브랜치 생성 흐름:
     * - develop 기준으로 feature/login 생성
     * - master 기준으로 hotfix/security 생성
     *
     * 값이 없으면 백엔드에서 master를 기본값으로 사용합니다.
     */
    private String baseBranch;

    /*
     * 브랜치 생성 후 해당 브랜치로 이동할지 여부입니다.
     *
     * 실제 체크아웃은 프론트의 activeBranch 변경과 파일 트리 갱신으로 처리합니다.
     * 백엔드에서는 브랜치 생성 요청의 옵션값으로만 보관합니다.
     */
    private Boolean checkoutAfterCreate;

    // [핵심] '..'을 사용해서 상위 폴더로 몰래 빠져나가는 해킹 방지
    @NotBlank(message = "경로는 필수입니다.")
    private String filePath;

    private String code;

    /*
     * 빈 내용으로 덮어쓰는 것을 허용할지 여부입니다.
     *
     * 동시편집 자동 저장이 동기화가 끝나기 전에 저장을 보내면, 아직 비어
     * 있는 문서가 그대로 디스크에 쓰이면서 멀쩡한 파일이 통째로 날아갑니다.
     * 그래서 서버는 기본적으로 빈 내용 덮어쓰기를 막고, 사용자가 직접
     * 비우려고 누른 저장만 이 값을 실어 보냅니다.
     */
    private Boolean allowEmpty;

    @Pattern(regexp = "^(?i)(file|folder)$", message = "타입은 'file' 또는 'folder'만 가능합니다.")
    private String type;

    @Pattern(regexp = "^[^\\\\/:*?\"<>|]+$", message = "파일명에 사용할 수 없는 특수문자가 있습니다.")
    private String newName;

    /*
     * 파일 이동(탐색기 드래그) 대상 폴더. 브랜치 폴더 기준 상대경로이고 "" 이면 최상위다.
     * 값이 있으면 이름은 그대로 두고 이 폴더로 옮긴다(newName 은 무시).
     */
    private String targetFolder;
}