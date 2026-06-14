package com.myide.backend.dto.github;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GithubCommitResponse(
        String sha,
        Commit commit,
        GithubUser author
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Commit(
            CommitAuthor author,
            String message
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CommitAuthor(
            String name,
            String email,
            String date
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GithubUser(
            String login
    ) {
    }
}