package com.myide.backend.service;

import com.myide.backend.domain.User;
import com.myide.backend.dto.mypage.ActivityHeatmapDayResponse;
import com.myide.backend.dto.mypage.ActivityHeatmapResponse;
import com.myide.backend.dto.mypage.DateCountRow;
import com.myide.backend.repository.DevlogRepository;
import com.myide.backend.repository.ProjectRepository;
import com.myide.backend.repository.ScheduleRepository;
import com.myide.backend.repository.UserRepository;
import com.myide.backend.service.github.GithubCommitService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
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

    public ActivityHeatmapResponse getMyActivityHeatmap(Long userId, int days) {
        int safeDays = Math.max(7, Math.min(days, 365));

        LocalDate endDate = LocalDate.now();
        LocalDate startDate = endDate.minusDays(safeDays - 1L);

        LocalDateTime startDateTime = startDate.atStartOfDay();
        LocalDateTime endDateTime = endDate.plusDays(1).atStartOfDay();

        Map<LocalDate, Integer> countMap = new LinkedHashMap<>();

        for (int i = 0; i < safeDays; i++) {
            LocalDate date = startDate.plusDays(i);
            countMap.put(date, 0);
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("사용자를 찾을 수 없습니다."));

        int devlogCount = applyDateCountRows(
                countMap,
                devlogRepository.countMyDevlogsByWorkedDate(
                        userId,
                        startDate,
                        endDate
                )
        );

        int scheduleDoneCount = applyDateCountRows(
                countMap,
                scheduleRepository.countMyDoneSchedulesByUpdatedDate(
                        userId,
                        startDateTime,
                        endDateTime
                )
        );

        int commitCount = applyGithubCommitCounts(
                user,
                startDate,
                endDate,
                countMap
        );

        List<ActivityHeatmapDayResponse> dayResponses = countMap.entrySet()
                .stream()
                .map(entry -> new ActivityHeatmapDayResponse(
                        entry.getKey(),
                        entry.getValue(),
                        toLevel(entry.getValue())
                ))
                .toList();

        int totalActivityCount = countMap.values()
                .stream()
                .mapToInt(Integer::intValue)
                .sum();

        int activeDays = (int) countMap.values()
                .stream()
                .filter(count -> count > 0)
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

    private int applyGithubCommitCounts(
            User user,
            LocalDate startDate,
            LocalDate endDate,
            Map<LocalDate, Integer> countMap
    ) {
        String accessToken = user.getGithubAccessToken();
        String githubUsername = user.getGithubUsername();

        if (accessToken == null || accessToken.isBlank()) {
            return 0;
        }

        if (githubUsername == null || githubUsername.isBlank()) {
            return 0;
        }

        List<String> gitUrls = projectRepository.findLinkedGitUrlsByOwnerOrAcceptedMember(
                user.getId()
        );

        if (gitUrls == null || gitUrls.isEmpty()) {
            return 0;
        }

        Map<LocalDate, Integer> commitMap =
                githubCommitService.getCommitCountByDate(
                        accessToken,
                        githubUsername,
                        user.getEmail(),
                        gitUrls,
                        startDate,
                        endDate
                );

        int total = 0;

        for (Map.Entry<LocalDate, Integer> entry : commitMap.entrySet()) {
            LocalDate date = entry.getKey();
            int count = entry.getValue();

            addCount(countMap, date, count);
            total += count;
        }

        return total;
    }

    private int applyDateCountRows(
            Map<LocalDate, Integer> countMap,
            List<DateCountRow> rows
    ) {
        if (rows == null || rows.isEmpty()) {
            return 0;
        }

        int total = 0;

        for (DateCountRow row : rows) {
            if (row == null || row.getDate() == null || row.getCount() == null) {
                continue;
            }

            int count = row.getCount().intValue();

            addCount(countMap, row.getDate(), count);
            total += count;
        }

        return total;
    }

    private void addCount(
            Map<LocalDate, Integer> countMap,
            LocalDate date,
            int count
    ) {
        if (date == null) return;
        if (!countMap.containsKey(date)) return;
        if (count <= 0) return;

        countMap.put(date, countMap.get(date) + count);
    }

    private int toLevel(int count) {
        if (count <= 0) return 0;
        if (count == 1) return 1;
        if (count == 2) return 2;
        if (count == 3) return 3;
        return 4;
    }
}