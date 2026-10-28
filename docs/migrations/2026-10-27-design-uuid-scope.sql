-- =====================================================================
-- 설계 요구사항 / API 명세 uuid 를 "워크스페이스 안에서만 유일"하게 바꾼다.
--
-- 왜: 프로젝트를 복제하면 설계 문서(Yjs)의 항목 id 가 원본과 같다. 역투영 행
--     (design_requirements / design_api_specs)의 uuid 가 바로 그 id 라서, 전역 UNIQUE 가
--     남아 있으면 복제 자체가 실패한다(409, "마이그레이션이 필요합니다").
--
-- 무엇을: (1) (workspace_uuid, uuid) 복합 UNIQUE 를 먼저 만들고
--         (2) uuid 단일 UNIQUE 인덱스를 지운다.
--         순서를 이렇게 두어 "아무 제약도 없는 순간"이 생기지 않게 한다.
--         행 데이터는 읽기만 하고 바꾸거나 지우지 않는다. DROP TABLE 없음.
--
-- ddl-auto: update 는 (1)은 스스로 하지만 (2)는 하지 않는다. 서버를 새 코드로
-- 한 번 띄웠다면 (1)은 이미 되어 있을 수 있고, 이 스크립트는 그 경우를 건너뛴다.
--
-- 인덱스 이름은 Hibernate 가 해시로 지은 것이라 DB 마다 같다고 보장할 수 없어서
-- information_schema 에서 찾아 지운다. 2026-10-27 로컬 DB(myide) 에서 확인한 이름:
--   design_requirements.UK4u806o79wbfqnwjd6e3tj99hc (uuid)
--   design_api_specs.UKka4431m7oh2ys4mld25osh380    (uuid)
--
-- 적용 방법
--   0) 백업:  mysqldump -u <user> -p --single-transaction <db> design_requirements design_api_specs > design_uuid_backup.sql
--   1) 아래 [사전 확인] 결과를 본다.
--   2) 스크립트 전체 실행:  mysql -u <user> -p <db> < 2026-10-27-design-uuid-scope.sql
--   3) [사후 확인] 결과에서 uuid 단일 UNIQUE 가 없고 복합 UNIQUE 가 있는지 본다.
--   MySQL 8 의 ALTER 는 각각 자동 커밋된다. 두 테이블은 작으므로 잠금 시간은 짧다.
-- =====================================================================

-- ---------------------------------------------------------------------
-- [사전 확인] 현재 UNIQUE 인덱스
-- ---------------------------------------------------------------------
SELECT TABLE_NAME, INDEX_NAME, GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) AS cols
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME IN ('design_requirements', 'design_api_specs')
  AND NON_UNIQUE = 0
GROUP BY TABLE_NAME, INDEX_NAME;


-- =====================================================================
-- design_requirements
-- =====================================================================

-- (1) 복합 UNIQUE (없을 때만)
SET @exists := (
    SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'design_requirements'
      AND INDEX_NAME = 'uk_design_requirements_workspace_uuid'
);
SET @sql := IF(@exists = 0,
    'ALTER TABLE design_requirements ADD CONSTRAINT uk_design_requirements_workspace_uuid UNIQUE (workspace_uuid, uuid)',
    'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- (2) uuid 단일 UNIQUE 제거 (컬럼이 uuid 하나뿐인 UNIQUE 인덱스만 대상)
--     (workspace_uuid, uuid) UNIQUE 가 실제로 있을 때만 지운다. (1)이 실패한 채로
--     여기까지 와도(콘솔에서 오류 후 계속 실행 등) uuid 제약이 통째로 사라지지 않게 한다.
SET @composite := (
    SELECT COUNT(*) FROM (
        SELECT INDEX_NAME FROM information_schema.STATISTICS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 'design_requirements'
          AND NON_UNIQUE = 0
        GROUP BY INDEX_NAME
        HAVING GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) = 'workspace_uuid,uuid'
    ) c
);
SET @old := (
    SELECT INDEX_NAME FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'design_requirements'
      AND NON_UNIQUE = 0
      AND INDEX_NAME <> 'PRIMARY'
    GROUP BY INDEX_NAME
    HAVING COUNT(*) = 1 AND MAX(COLUMN_NAME) = 'uuid'
    LIMIT 1
);
SET @sql := IF(@old IS NULL OR @composite = 0, 'DO 0',
    CONCAT('ALTER TABLE design_requirements DROP INDEX `', @old, '`'));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;


-- =====================================================================
-- design_api_specs
-- =====================================================================

SET @exists := (
    SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'design_api_specs'
      AND INDEX_NAME = 'uk_design_api_specs_workspace_uuid'
);
SET @sql := IF(@exists = 0,
    'ALTER TABLE design_api_specs ADD CONSTRAINT uk_design_api_specs_workspace_uuid UNIQUE (workspace_uuid, uuid)',
    'DO 0');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @composite := (
    SELECT COUNT(*) FROM (
        SELECT INDEX_NAME FROM information_schema.STATISTICS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 'design_api_specs'
          AND NON_UNIQUE = 0
        GROUP BY INDEX_NAME
        HAVING GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) = 'workspace_uuid,uuid'
    ) c
);
SET @old := (
    SELECT INDEX_NAME FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'design_api_specs'
      AND NON_UNIQUE = 0
      AND INDEX_NAME <> 'PRIMARY'
    GROUP BY INDEX_NAME
    HAVING COUNT(*) = 1 AND MAX(COLUMN_NAME) = 'uuid'
    LIMIT 1
);
SET @sql := IF(@old IS NULL OR @composite = 0, 'DO 0',
    CONCAT('ALTER TABLE design_api_specs DROP INDEX `', @old, '`'));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;


-- ---------------------------------------------------------------------
-- [사후 확인] uuid 단일 UNIQUE 는 없고, (workspace_uuid, uuid) 만 있어야 한다.
-- ---------------------------------------------------------------------
SELECT TABLE_NAME, INDEX_NAME, GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) AS cols
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME IN ('design_requirements', 'design_api_specs')
  AND NON_UNIQUE = 0
GROUP BY TABLE_NAME, INDEX_NAME;


-- =====================================================================
-- 되돌리기(필요할 때만, 수동)
--
-- 복제본이 하나라도 생긴 뒤에는 같은 uuid 가 두 워크스페이스에 있으므로 전역 UNIQUE 를
-- 다시 걸 수 없다. 먼저 아래 조회가 0 건인지 확인한 뒤에만 실행한다.
--
--   SELECT uuid, COUNT(*) FROM design_requirements GROUP BY uuid HAVING COUNT(*) > 1;
--   SELECT uuid, COUNT(*) FROM design_api_specs    GROUP BY uuid HAVING COUNT(*) > 1;
--
--   ALTER TABLE design_requirements ADD CONSTRAINT UK4u806o79wbfqnwjd6e3tj99hc UNIQUE (uuid);
--   ALTER TABLE design_api_specs    ADD CONSTRAINT UKka4431m7oh2ys4mld25osh380 UNIQUE (uuid);
-- =====================================================================
