package com.backtoback.integration.github.client.dto;

/**
 * GitHub Contents API 파일 커밋 응답 (PUT /repos/{owner}/{repo}/contents/{path}).
 */
public record GithubCommitResultResponse(Commit commit) {

    /**
     * 생성된 커밋 정보.
     *
     * @param sha 커밋 SHA
     */
    public record Commit(String sha) {
    }

    /**
     * 생성된 커밋의 SHA를 반환한다.
     *
     * @return 커밋 SHA, 응답에 커밋 정보가 없으면 null
     */
    public String commitSha() {
        return commit == null ? null : commit.sha();
    }
}
