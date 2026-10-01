// 경로: src/main/java/com/myide/backend/service/saferun/RunPlanner.java
package com.myide.backend.service.saferun;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 실행 한 번에서 "이번에는 누구의 변경을 넣고 빌드할지"를 정한다.
 *
 * <pre>
 *   빌드 1  준비된(READY) 작성자 전원 + 본인
 *     실패 → 에러 파일의 작성자를 장부에서 찾는다
 *       · 에러 파일이 전부 본인이 고친 파일           → 에러를 보여 주고 끝
 *       · 다른 사람이 고친 파일에서 에러               → 빌드 2
 *       · 누가 고쳤는지 모르는 파일에서만 에러         → 빌드 2 를 건너뛰고 빌드 3
 *   빌드 2  찾은 작성자를 제외 (본인은 절대 제외하지 않는다)
 *     실패 → 빌드 3
 *   빌드 3  green + 본인 변경만
 * </pre>
 *
 * 운영 서버가 2GB 한 대라 빌드 횟수가 곧 비용이다. 그래서 결과가 달라질 수 없는 빌드는
 * 건너뛴다(본인이 고친 파일은 누구를 제외해도 그대로이므로 다시 빌드해도 같다).
 *
 * 파일 연산도 Docker 도 없는 순수 판단이라 따로 떼어 테스트한다.
 */
public class RunPlanner {

    public enum Mode {
        /** 정상 버전이 아직 없다. live 그대로 한 번만 빌드한다. */
        NO_GREEN,
        /** 빌드 1 */
        FULL,
        /** 빌드 2 */
        EXCLUDED,
        /** 빌드 3 */
        OWN_ONLY
    }

    /**
     * @param number                몇 번째 빌드인지(1부터)
     * @param included              변경을 넣을 작성자
     * @param includeUnknownChanges 장부에 없는 변경(출처 모름)을 넣을지
     */
    public record Attempt(int number, Mode mode, Set<Long> included, boolean includeUnknownChanges) {}

    /** 본인과 다른 사람이 함께 고친 파일. 다른 사람을 제외해도 이 파일은 되돌릴 수 없다. */
    public record SharedFile(String file, Set<Long> coAuthors) {}

    private final Map<String, Set<Long>> ledger;
    private final Long requester;
    private final boolean hasGreen;
    private final int maxBuilds;

    /**
     * @param ledger    편집 장부 사본. 실행 도중 장부가 바뀌어도 판단 기준이 흔들리지 않게 사본을 받는다
     * @param requester 실행한 본인
     * @param hasGreen  정상 버전이 있는지
     * @param maxBuilds 이 실행에서 허용하는 최대 빌드 횟수(1~3)
     */
    public RunPlanner(Map<String, Set<Long>> ledger, Long requester, boolean hasGreen, int maxBuilds) {
        this.ledger = ledger;
        this.requester = requester;
        this.hasGreen = hasGreen;
        this.maxBuilds = Math.max(1, Math.min(3, maxBuilds));
    }

    /**
     * 첫 빌드.
     *
     * @param readyAuthors 지금 준비됨(READY)인 작성자
     * @param userIncluded 사용자가 직접 고른 포함 목록. 고르지 않았으면 null 또는 빈 집합
     * @param userExcluded 사용자가 직접 고른 제외 목록
     */
    public Attempt first(Set<Long> readyAuthors, Set<Long> userIncluded, Set<Long> userExcluded) {
        if (!hasGreen) {
            // 되돌아갈 정상 버전이 없으니 누구를 빼고 말고가 없다.
            return new Attempt(1, Mode.NO_GREEN, withRequester(allAuthors()), true);
        }

        Set<Long> included = new TreeSet<>(
                userIncluded != null && !userIncluded.isEmpty() ? userIncluded : readyAuthors);

        if (userExcluded != null) {
            included.removeAll(userExcluded);
        }

        return new Attempt(1, Mode.FULL, withRequester(included), true);
    }

    /**
     * 빌드가 실패했을 때 다음 빌드. 더 해 볼 것이 없으면 null.
     *
     * @param errorFiles 컴파일러 출력에서 뽑은 에러 파일(브랜치 폴더 기준 상대경로)
     */
    public Attempt afterFailure(Attempt failed, Set<String> errorFiles) {
        if (failed.mode() == Mode.NO_GREEN || failed.mode() == Mode.OWN_ONLY) {
            return null;
        }

        if (failed.number() >= maxBuilds) {
            return null;
        }

        Attempt ownOnly = new Attempt(failed.number() + 1, Mode.OWN_ONLY, withRequester(Set.of()), false);

        if (failed.mode() == Mode.EXCLUDED) {
            return ownOnly;
        }

        // 여기부터 빌드 1 이 실패한 경우.
        if (errorFiles == null || errorFiles.isEmpty()) {
            // 어느 파일인지 알아내지 못했다. 누구를 빼야 할지 모르니 가장 안전한 조합으로 간다.
            return ownOnly;
        }

        boolean allMine = errorFiles.stream().allMatch(file -> authorsOf(file).contains(requester));

        if (allMine) {
            // 본인이 고친 파일은 어떤 조합에서도 live 그대로 들어간다. 다시 빌드해도 같은
            // 에러가 나므로 빌드를 아끼고 에러를 그대로 보여 준다.
            return null;
        }

        Set<Long> culprits = new TreeSet<>();
        for (String file : errorFiles) {
            Set<Long> authors = authorsOf(file);

            // 본인이 함께 고친 파일은 다른 작성자를 제외해도 되돌리지 않는다. 그 파일을
            // 이유로 사람을 빼 봐야 달라지는 것이 없다.
            if (!authors.contains(requester)) {
                culprits.addAll(authors);
            }
        }

        culprits.retainAll(failed.included());

        if (culprits.isEmpty()) {
            // 에러 파일을 고친 사람이 장부에 없다(출처를 모르는 변경). 뺄 사람이 없으니
            // 빌드 2 를 건너뛴다.
            return ownOnly;
        }

        if (maxBuilds < 3) {
            // 빌드를 두 번만 허용하는 유형(무거운 빌드)은 중간 단계 없이 바로 간다.
            return ownOnly;
        }

        Set<Long> included = new TreeSet<>(failed.included());
        included.removeAll(culprits);

        return new Attempt(failed.number() + 1, Mode.EXCLUDED, withRequester(included), true);
    }

    /** 이 빌드에서 변경이 빠진 작성자. */
    public Set<Long> excludedAuthors(Attempt attempt) {
        Set<Long> excluded = allAuthors();
        excluded.removeAll(attempt.included());
        return excluded;
    }

    /**
     * 제외된 작성자의 파일 가운데 실제로 green 으로 되돌아간 것.
     * 본인이 함께 고친 파일은 되돌리지 않으므로 빠진다.
     */
    public List<String> revertedFilesOf(Long author, Attempt attempt) {
        List<String> files = new ArrayList<>();

        ledger.forEach((file, authors) -> {
            if (authors.contains(author) && !RunAssembler.wantsLive(authors, requester, attempt.included())) {
                files.add(file);
            }
        });

        files.sort(null);
        return files;
    }

    /** 주어진 파일들 가운데 본인과 다른 사람이 함께 고친 파일. */
    public List<SharedFile> sharedFiles(Collection<String> files) {
        List<SharedFile> shared = new ArrayList<>();

        if (files == null) {
            return shared;
        }

        for (String file : new TreeSet<>(files)) {
            Set<Long> authors = authorsOf(file);

            if (authors.contains(requester) && authors.size() > 1) {
                Set<Long> others = new TreeSet<>(authors);
                others.remove(requester);
                shared.add(new SharedFile(file, others));
            }
        }

        return shared;
    }

    /**
     * 이 빌드가 성공하면 정상 버전(green)으로 삼아도 되는지.
     *
     * 누군가의 변경이 빠진 조합을 green 으로 삼으면 그 사람의 변경이 "이미 반영된 것"처럼
     * 장부에서 지워질 수 있다. 그래서 아무도 빠지지 않은 조합만 승격한다.
     */
    public boolean isPromotable(Attempt attempt) {
        if (attempt.mode() == Mode.NO_GREEN) {
            return true;
        }

        return attempt.mode() == Mode.FULL && attempt.included().containsAll(allAuthors());
    }

    private Set<Long> authorsOf(String file) {
        Set<Long> authors = ledger.get(EditLedger.normalizePath(file));
        return authors == null ? Set.of() : authors;
    }

    private Set<Long> allAuthors() {
        Set<Long> all = new TreeSet<>();
        ledger.values().forEach(all::addAll);
        return all;
    }

    private Set<Long> withRequester(Set<Long> authors) {
        Set<Long> result = new TreeSet<>(authors);
        result.add(requester);
        return result;
    }
}
