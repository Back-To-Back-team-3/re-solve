import type { Page } from "@playwright/test";

export const problemDetail = {
  problemId: "1001", problemRevisionId: "2001", revisionNumber: 1,
  title: "고정 회차 배열 합", difficulty: 1, tags: [{ tagId: "1", name: "배열" }],
  publishedAt: "2026-11-13T00:00:00.000000+09:00", solvedStatus: "UNSOLVED",
  description: "배열의 합을 반환하세요.", constraints: "원소는 100 이하", examples: "예시",
  functionSpec: {
    name: "solution",
    parameters: [{ name: "items", type: { kind: "ARRAY", elementType: { kind: "INT32" } } }],
    returnType: { kind: "INT32" },
  },
  executionLimits: { timeMs: 1000, memoryKb: 262144, wallTimeMs: 3000, outputLimitKb: 64 },
  supportedLanguages: ["java", "python3"],
  starterCodes: [
    { language: "java", sourceCode: "// java starter" },
    { language: "python3", sourceCode: "# python starter" },
  ],
  samples: [{ testAssetId: "1", argumentTexts: { items: "[1,2]" }, expectedReturnText: "3" }],
};

/** 실서버 계약의 HTTP 응답으로 문제 화면을 실행한다. 채점 서버는 호출하지 않는다. */
export async function mockPracticePage(page: Page, enabled = true) {
  await page.route("**/api/v1/**", async (route) => {
    const path = new URL(route.request().url()).pathname;
    let data: unknown;
    if (path === "/api/v1/auth/token/refresh") data = { accessToken: "test-access", expiresIn: 3600 };
    else if (path === "/api/v1/members/me") data = {
      memberId: "7", nickname: "테스터", role: "USER", status: "ACTIVE",
      githubLogin: "tester", profileImageUrl: null, email: null, hasLearningProfile: true,
      createdAt: "2026-11-13T00:00:00.000000+09:00",
    };
    else if (path === "/api/v1/problems/1001") data = problemDetail;
    else if (path === "/api/v1/judge/languages") data = { content: [
      { language: "java", displayName: "Java 17", runtimeVersion: "17", enabled },
      { language: "python3", displayName: "Python 3", runtimeVersion: "3", enabled: false },
      { language: "cpp", displayName: "C++17", runtimeVersion: "17", enabled: true },
    ] };
    else if (path === "/api/v1/me/submissions") data = {
      content: [], page: 0, size: 20, totalElements: 0, totalPages: 0, hasNext: false,
    };
    else {
      await route.fulfill({ status: 404, json: { success: false, error: { code: "NOT_FOUND", message: path } } });
      return;
    }
    await route.fulfill({ json: { success: true, message: null, data } });
  });
}
