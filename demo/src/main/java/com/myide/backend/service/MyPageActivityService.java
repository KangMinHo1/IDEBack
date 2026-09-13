package com.myide.backend.service;

import com.myide.backend.domain.User;
import com.myide.backend.domain.devlog.Devlog;
import com.myide.backend.domain.schedule.Schedule;
import com.myide.backend.domain.schedule.ScheduleStatus;

import com.myide.backend.dto.mypage.ActivityHeatmapDayResponse;
import com.myide.backend.dto.mypage.ActivityHeatmapResponse;
import com.myide.backend.dto.mypage.DateCountRow;
import com.myide.backend.dto.mypage.RecentActivityResponse;

import com.myide.backend.repository.DevlogRepository;
import com.myide.backend.repository.ProjectRepository;
import com.myide.backend.repository.ScheduleRepository;
import com.myide.backend.repository.UserRepository;

import com.myide.backend.service.github.GithubCommitService;

import lombok.RequiredArgsConstructor;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class MyPageActivityService {

    private final UserRepository userRepository;
    private final DevlogRepository devlogRepository;
    private final ScheduleRepository scheduleRepository;
    private final ProjectRepository projectRepository;
    private final GithubCommitService githubCommitService;

    /* =========================================================
       활동 히트맵
       ========================================================= */

    @Transactional(readOnly = true)
    public ActivityHeatmapResponse getMyActivityHeatmap(
            Long userId,
            int days
    ) {
        int safeDays =
                Math.max(
                        7,
                        Math.min(days, 365)
                );

        LocalDate endDate =
                LocalDate.now();

        LocalDate startDate =
                endDate.minusDays(
                        safeDays - 1L
                );

        LocalDateTime startDateTime =
                startDate.atStartOfDay();

        LocalDateTime endDateTime =
                endDate
                        .plusDays(1)
                        .atStartOfDay();

        Map<LocalDate, Integer> countMap =
                new LinkedHashMap<>();

        for (
                int i = 0;
                i < safeDays;
                i++
        ) {
            LocalDate date =
                    startDate.plusDays(i);

            countMap.put(
                    date,
                    0
            );
        }

        User user =
                userRepository
                        .findById(userId)
                        .orElseThrow(
                                () ->
                                        new RuntimeException(
                                                "사용자를 찾을 수 없습니다."
                                        )
                        );

        int devlogCount =
                applyDateCountRows(
                        countMap,
                        devlogRepository
                                .countMyDevlogsByWorkedDate(
                                        userId,
                                        startDate,
                                        endDate
                                )
                );

        /*
         * updatedAt 대신
         * completedAt 기준.
         */
        int scheduleDoneCount =
                applyDateCountRows(
                        countMap,
                        scheduleRepository
                                .countMyDoneSchedulesByCompletedDate(
                                        userId,
                                        startDateTime,
                                        endDateTime
                                )
                );

        int commitCount =
                applyGithubCommitCounts(
                        user,
                        startDate,
                        endDate,
                        countMap
                );

        List<ActivityHeatmapDayResponse>
                dayResponses =
                countMap
                        .entrySet()
                        .stream()
                        .map(
                                entry ->
                                        new ActivityHeatmapDayResponse(
                                                entry.getKey(),
                                                entry.getValue(),
                                                toLevel(
                                                        entry.getValue()
                                                )
                                        )
                        )
                        .toList();

        int totalActivityCount =
                countMap
                        .values()
                        .stream()
                        .mapToInt(
                                Integer::intValue
                        )
                        .sum();

        int activeDays =
                (int) countMap
                        .values()
                        .stream()
                        .filter(
                                count ->
                                        count > 0
                        )
                        .count();

        return new ActivityHeatmapResponse(
                dayResponses,
                totalActivityCount,
                activeDays,
                devlogCount,
                scheduleDoneCount,
                commitCount
        );
    }

    /* =========================================================
       최근 활동
       ========================================================= */

    @Transactional(readOnly = true)
    public List<RecentActivityResponse> getMyRecentActivities(
            Long userId,
            int limit
    ) {
        int safeLimit =
                Math.max(
                        1,
                        Math.min(limit, 20)
                );

        User user =
                userRepository
                        .findById(userId)
                        .orElseThrow(
                                () ->
                                        new RuntimeException(
                                                "사용자를 찾을 수 없습니다."
                                        )
                        );

        /*
         * 각 종류를 조금 넉넉하게 조회한 뒤
         * 전체를 합쳐 최신순으로 잘라낸다.
         */
        Pageable pageable =
                PageRequest.of(
                        0,
                        Math.min(
                                safeLimit * 3,
                                60
                        )
                );

        List<RecentActivityResponse>
                activities =
                new ArrayList<>();

        /* =========================
           개발일지 작성
           ========================= */

        List<Devlog> recentDevlogs =
                devlogRepository
                        .findByCreatedBy_IdOrderByCreatedAtDesc(
                                userId,
                                pageable
                        );

        for (Devlog devlog : recentDevlogs) {

            String workspaceId =
                    devlog.getWorkspace() != null
                            ? devlog
                            .getWorkspace()
                            .getUuid()
                            : null;

            String workspaceName =
                    devlog.getWorkspace() != null
                            ? devlog
                            .getWorkspace()
                            .getName()
                            : null;

            String description =
                    workspaceName == null
                            ? devlog.getTitle()
                            : workspaceName
                            + " · "
                            + devlog.getTitle();

            activities.add(
                    new RecentActivityResponse(
                            "devlog-"
                                    + devlog.getUuid(),

                            "DEVLOG_CREATED",

                            "개발일지 작성",

                            description,

                            workspaceId,

                            workspaceName,

                            devlog.getCreatedAt()
                    )
            );
        }

        /* =========================
           일정 완료
           ========================= */

        List<Schedule> recentSchedules =
                scheduleRepository
                        .findByCreatedBy_IdAndStatusAndCompletedAtIsNotNullOrderByCompletedAtDesc(
                                userId,
                                ScheduleStatus.DONE,
                                pageable
                        );

        for (
                Schedule schedule
                : recentSchedules
        ) {

            String workspaceId =
                    schedule.getWorkspace() != null
                            ? schedule
                            .getWorkspace()
                            .getUuid()
                            : null;

            String workspaceName =
                    schedule.getWorkspace() != null
                            ? schedule
                            .getWorkspace()
                            .getName()
                            : null;

            String description =
                    workspaceName == null
                            ? schedule.getTitle()
                            : workspaceName
                            + " · "
                            + schedule.getTitle();

            activities.add(
                    new RecentActivityResponse(
                            "schedule-"
                                    + schedule.getUuid(),

                            "SCHEDULE_COMPLETED",

                            "일정 완료",

                            description,

                            workspaceId,

                            workspaceName,

                            schedule.getCompletedAt()
                    )
            );
        }

        /* =========================
           GitHub 커밋

           현재 GithubCommitService는
           날짜별 개수만 제공하므로
           일 단위 활동으로 표시한다.
           ========================= */

        addRecentGithubActivities(
                user,
                activities
        );

        /* =========================
           전체 최신순
           ========================= */

        return activities
                .stream()
                .filter(
                        activity ->
                                activity.occurredAt()
                                        != null
                )
                .sorted(
                        Comparator.comparing(
                                RecentActivityResponse
                                        ::occurredAt
                        ).reversed()
                )
                .limit(
                        safeLimit
                )
                .toList();
    }

    /* =========================================================
       최근 GitHub 활동
       ========================================================= */

    private void addRecentGithubActivities(
            User user,
            List<RecentActivityResponse> activities
    ) {
        String accessToken =
                user.getGithubAccessToken();

        String githubUsername =
                user.getGithubUsername();

        if (
                accessToken == null
                        || accessToken.isBlank()
        ) {
            return;
        }

        if (
                githubUsername == null
                        || githubUsername.isBlank()
        ) {
            return;
        }

        List<String> gitUrls =
                projectRepository
                        .findLinkedGitUrlsByOwnerOrAcceptedMember(
                                user.getId()
                        );

        if (
                gitUrls == null
                        || gitUrls.isEmpty()
        ) {
            return;
        }

        /*
         * 최근 활동에서는 최대 365일 안에서
         * 실제 커밋이 있었던 날을 찾는다.
         */
        LocalDate endDate =
                LocalDate.now();

        LocalDate startDate =
                endDate.minusDays(364);

        Map<LocalDate, Integer>
                commitMap =
                githubCommitService
                        .getCommitCountByDate(
                                accessToken,
                                githubUsername,
                                user.getEmail(),
                                gitUrls,
                                startDate,
                                endDate
                        );

        if (
                commitMap == null
                        || commitMap.isEmpty()
        ) {
            return;
        }

        for (
                Map.Entry<LocalDate, Integer>
                        entry
                : commitMap.entrySet()
        ) {

            LocalDate date =
                    entry.getKey();

            Integer count =
                    entry.getValue();

            if (
                    date == null
                            || count == null
                            || count <= 0
            ) {
                continue;
            }

            activities.add(
                    new RecentActivityResponse(
                            "github-" + date,

                            "GITHUB_COMMIT",

                            "GitHub 커밋",

                            count
                                    + "개의 커밋",

                            null,

                            null,

                            /*
                             * 현재 GitHub 서비스가 날짜 단위만
                             * 제공하므로 정확한 시각은 알 수 없음.
                             *
                             * 화면에서는 날짜까지만 보여주는 것이 맞다.
                             */
                            date.atStartOfDay()
                    )
            );
        }
    }

    /* =========================================================
       GitHub 히트맵
       ========================================================= */

    private int applyGithubCommitCounts(
            User user,
            LocalDate startDate,
            LocalDate endDate,
            Map<LocalDate, Integer> countMap
    ) {
        String accessToken =
                user.getGithubAccessToken();

        String githubUsername =
                user.getGithubUsername();

        if (
                accessToken == null
                        || accessToken.isBlank()
        ) {
            return 0;
        }

        if (
                githubUsername == null
                        || githubUsername.isBlank()
        ) {
            return 0;
        }

        List<String> gitUrls =
                projectRepository
                        .findLinkedGitUrlsByOwnerOrAcceptedMember(
                                user.getId()
                        );

        if (
                gitUrls == null
                        || gitUrls.isEmpty()
        ) {
            return 0;
        }

        Map<LocalDate, Integer>
                commitMap =
                githubCommitService
                        .getCommitCountByDate(
                                accessToken,
                                githubUsername,
                                user.getEmail(),
                                gitUrls,
                                startDate,
                                endDate
                        );

        int total = 0;

        for (
                Map.Entry<LocalDate, Integer>
                        entry
                : commitMap.entrySet()
        ) {
            LocalDate date =
                    entry.getKey();

            int count =
                    entry.getValue();

            addCount(
                    countMap,
                    date,
                    count
            );

            total += count;
        }

        return total;
    }

    /* =========================================================
       공통
       ========================================================= */

    private int applyDateCountRows(
            Map<LocalDate, Integer> countMap,
            List<DateCountRow> rows
    ) {
        if (
                rows == null
                        || rows.isEmpty()
        ) {
            return 0;
        }

        int total = 0;

        for (DateCountRow row : rows) {
            if (
                    row == null
                            || row.getDate() == null
                            || row.getCount() == null
            ) {
                continue;
            }

            int count =
                    row
                            .getCount()
                            .intValue();

            addCount(
                    countMap,
                    row.getDate(),
                    count
            );

            total += count;
        }

        return total;
    }

    private void addCount(
            Map<LocalDate, Integer> countMap,
            LocalDate date,
            int count
    ) {
        if (date == null) {
            return;
        }

        if (!countMap.containsKey(date)) {
            return;
        }

        if (count <= 0) {
            return;
        }

        countMap.put(
                date,
                countMap.get(date)
                        + count
        );
    }

    private int toLevel(int count) {
        if (count <= 0) {
            return 0;
        }

        if (count == 1) {
            return 1;
        }

        if (count == 2) {
            return 2;
        }

        if (count == 3) {
            return 3;
        }

        return 4;
    }
}