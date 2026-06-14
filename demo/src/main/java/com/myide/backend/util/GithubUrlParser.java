package com.myide.backend.util;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class GithubUrlParser {

    private static final Pattern HTTPS_PATTERN =
            Pattern.compile("^https://github\\.com/([^/]+)/([^/]+?)(?:\\.git)?/?$");

    private static final Pattern SSH_PATTERN =
            Pattern.compile("^git@github\\.com:([^/]+)/([^/]+?)(?:\\.git)?$");

    private GithubUrlParser() {
    }

    public static Optional<GithubRepositoryInfo> parse(String gitUrl) {
        if (gitUrl == null || gitUrl.isBlank()) {
            return Optional.empty();
        }

        String trimmed = gitUrl.trim();

        Matcher httpsMatcher = HTTPS_PATTERN.matcher(trimmed);
        if (httpsMatcher.matches()) {
            return Optional.of(new GithubRepositoryInfo(
                    httpsMatcher.group(1),
                    httpsMatcher.group(2)
            ));
        }

        Matcher sshMatcher = SSH_PATTERN.matcher(trimmed);
        if (sshMatcher.matches()) {
            return Optional.of(new GithubRepositoryInfo(
                    sshMatcher.group(1),
                    sshMatcher.group(2)
            ));
        }

        return Optional.empty();
    }

    public record GithubRepositoryInfo(
            String owner,
            String repo
    ) {
    }
}