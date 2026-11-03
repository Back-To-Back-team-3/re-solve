package com.backtoback.integration.github.service;

import org.springframework.stereotype.Component;

/**
 * GitHub 자동 커밋 대상 파일 경로 및 파일명을 해석하고 생성하는 컴포넌트 (scheduler.md > 8.1, policy-and-state.md > 9.3).
 * 기본 템플릿: {@code {난이도}/{문제번호}-{문제명}/{언어별 파일}}
 */
@Component
public class GithubCommitPathResolver {

    /**
     * 커밋 경로 템플릿과 문제 및 언어 메타데이터를 결합하여 최종 GitHub 저장소 파일 경로를 반환한다.
     *
     * @param template 커밋 경로 규칙 템플릿
     * @param difficulty 난이도 (1~3)
     * @param problemId 문제 ID
     * @param problemTitle 문제명
     * @param language 제출 프로그래밍 언어 (예: python3, java, cpp, c)
     * @return 해석된 최종 저장소 파일 경로 (예: "1/100-두-수의-합/solution.py")
     */
    public String resolvePath(String template, int difficulty, Long problemId, String problemTitle, String language) {
        String baseRule = (template == null || template.isBlank()) ? "{난이도}/{문제번호}-{문제명}/{언어별 파일}" : template.trim();

        String filename = resolveFileName(language);
        String sanitizedTitle = sanitizeTitle(problemTitle);

        // 마지막 세그먼트가 이미 파일명 확장자를 가지거나 플레이스홀더인 경우 처리
        String path
            = baseRule
                .replace("{난이도}", String.valueOf(difficulty))
                .replace("{문제번호}", String.valueOf(problemId))
                .replace("{언어별 파일}", filename)
                .replace("{Solution.ext}", filename)
                .replace("풀이.java", filename)
                .replace("Solution.ext", filename)
                .replace("{문제명}", sanitizedTitle);

        // 만약 확장자가 경로 끝에 누락되어 있다면 파일명 추가
        if (!path.endsWith(filename) && !path.contains(".")) {
            path = path.endsWith("/") ? path + filename : path + "/" + filename;
        }

        // 연속 슬래시 정제
        return path.replaceAll("/{2,}", "/").replaceAll("^/", "");
    }

    /**
     * 제출 언어에 따른 표준 파일명을 결정한다.
     *
     * @param language 언어명
     * @return 파일명 (예: Solution.java, solution.py, solution.cpp, main.c)
     */
    public String resolveFileName(String language) {
        if (language == null) {
            return "Solution.txt";
        }
        String normalized = language.trim().toLowerCase();
        return switch (normalized) {
            case "java" -> "Solution.java";
            case "python", "python3", "py" -> "solution.py";
            case "cpp", "c++" -> "solution.cpp";
            case "c" -> "main.c";
            case "javascript", "js", "node" -> "solution.js";
            case "kotlin", "kt" -> "Solution.kt";
            default -> "solution." + normalized;
        };
    }

    private String sanitizeTitle(String title) {
        if (title == null || title.isBlank()) {
            return "problem";
        }
        // 경로 구분자(/, \) 및 특수문자를 공백/하이픈으로 치환
        String sanitized = title.trim().replaceAll("[/\\\\:*?\"<>|()\\[\\]{}]", "").replaceAll("\\s+", "-");
        return sanitized.isEmpty() ? "problem" : sanitized;
    }
}
