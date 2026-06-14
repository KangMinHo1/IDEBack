package com.myide.backend.service.github;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myide.backend.dto.github.GithubCommitResponse;
import com.myide.backend.util.GithubUrlParser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class GithubCommitService {

    private final ObjectMapper objectMapper;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    public Map<LocalDate, Integer> getCommitCountByDate(
            String accessToken,
            String githubUsername,
            String userEmail,
            List<String> gitUrls,
            LocalDate startDate,
            LocalDate endDate
    ) {
        Map<LocalDate, Integer> result = new HashMap<>();

        if (accessToken == null || accessToken.isBlank()) {
            return result;
        }

        if ((githubUsername == null || githubUsername.isBlank())
                && (userEmail == null || userEmail.isBlank())) {
            return result;
        }

        if (gitUrls == null || gitUrls.isEmpty()) {
            return result;
        }

        List<String> distinctGitUrls = gitUrls.stream()
                .filter(url -> url != null && !url.isBlank())
                .map(String::trim)
                .filter(url ->
                        url.startsWith("https://github.com/")
                                || url.startsWith("git@github.com:")
                )
                .distinct()
                .toList();

        for (String gitUrl : distinctGitUrls) {
            GithubUrlParser.parse(gitUrl).ifPresent(repoInfo -> {
                Map<LocalDate, Integer> repoCounts = fetchRepoCommits(
                        accessToken,
                        githubUsername,
                        userEmail,
                        repoInfo.owner(),
                        repoInfo.repo(),
                        startDate,
                        endDate
                );

                for (Map.Entry<LocalDate, Integer> entry : repoCounts.entrySet()) {
                    result.merge(entry.getKey(), entry.getValue(), Integer::sum);
                }
            });
        }

        return result;
    }

    private Map<LocalDate, Integer> fetchRepoCommits(
            String accessToken,
            String githubUsername,
            String userEmail,
            String owner,
            String repo,
            LocalDate startDate,
            LocalDate endDate
    ) {
        Map<LocalDate, Integer> result = new HashMap<>();

        int page = 1;
        int perPage = 100;

        while (page <= 10) {
            try {
                String since = startDate
                        .atStartOfDay()
                        .atOffset(ZoneOffset.UTC)
                        .toString();

                String until = endDate
                        .plusDays(1)
                        .atStartOfDay()
                        .atOffset(ZoneOffset.UTC)
                        .toString();

                URI uri = UriComponentsBuilder
                        .fromUriString("https://api.github.com/repos/{owner}/{repo}/commits")
                        .queryParam("since", since)
                        .queryParam("until", until)
                        .queryParam("per_page", perPage)
                        .queryParam("page", page)
                        .buildAndExpand(owner, repo)
                        .toUri();

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(uri)
                        .header("Authorization", "Bearer " + accessToken)
                        .header("Accept", "application/vnd.github+json")
                        .header("X-GitHub-Api-Version", "2022-11-28")
                        .GET()
                        .build();

                HttpResponse<String> response = httpClient.send(
                        request,
                        HttpResponse.BodyHandlers.ofString()
                );

                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    System.out.println("[github commits] 조회 실패: "
                            + owner + "/" + repo
                            + ", status=" + response.statusCode()
                            + ", body=" + response.body());
                    return result;
                }

                List<GithubCommitResponse> commits = objectMapper.readValue(
                        response.body(),
                        new TypeReference<List<GithubCommitResponse>>() {
                        }
                );

                if (commits.isEmpty()) {
                    return result;
                }

                for (GithubCommitResponse commit : commits) {
                    if (!isMyCommit(commit, githubUsername, userEmail)) {
                        continue;
                    }

                    String dateText = commit.commit() != null
                            && commit.commit().author() != null
                            ? commit.commit().author().date()
                            : null;

                    if (dateText == null || dateText.isBlank()) {
                        continue;
                    }

                    LocalDate commitDate = OffsetDateTime.parse(dateText).toLocalDate();

                    if (commitDate.isBefore(startDate) || commitDate.isAfter(endDate)) {
                        continue;
                    }

                    result.merge(commitDate, 1, Integer::sum);
                }

                if (commits.size() < perPage) {
                    return result;
                }

                page++;
            } catch (Exception e) {
                System.out.println("[github commits] 예외 발생: "
                        + owner + "/" + repo + " / " + e.getMessage());
                return result;
            }
        }

        return result;
    }

    private boolean isMyCommit(
            GithubCommitResponse commit,
            String githubUsername,
            String userEmail
    ) {
        String commitGithubLogin = commit.author() != null
                ? commit.author().login()
                : null;

        String commitEmail = commit.commit() != null
                && commit.commit().author() != null
                ? commit.commit().author().email()
                : null;

        if (githubUsername != null
                && !githubUsername.isBlank()
                && commitGithubLogin != null
                && githubUsername.equalsIgnoreCase(commitGithubLogin)) {
            return true;
        }

        return userEmail != null
                && !userEmail.isBlank()
                && commitEmail != null
                && userEmail.equalsIgnoreCase(commitEmail);
    }
}