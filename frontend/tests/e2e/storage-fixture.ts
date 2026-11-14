import { expect, type Page } from "@playwright/test";

/** 같은 출처에서 저장소를 초기화하고 접근 실패를 명시적인 테스트 실패로 처리한다. */
export async function seedBrowserStorage(page: Page, local: Record<string, string>, session: Record<string, string> = {}) {
  await page.goto("/login");
  const error = await page.evaluate(({ local, session }) => {
    try {
      for (const [key, value] of Object.entries(local)) localStorage.setItem(key, value);
      for (const [key, value] of Object.entries(session)) sessionStorage.setItem(key, value);
      return null;
    } catch (failure) { return failure instanceof Error ? failure.message : "브라우저 저장소 접근 실패"; }
  }, { local, session });
  expect(error, "브라우저 저장소를 초기화할 수 없습니다.").toBeNull();
}

/** 저장소 읽기 실패가 복원 검증의 성공으로 숨겨지지 않도록 한다. */
export async function readBrowserStorage(page: Page, key: string): Promise<string | null> {
  const result = await page.evaluate((key) => {
    try { return { value: localStorage.getItem(key), error: null }; }
    catch (failure) { return { value: null, error: failure instanceof Error ? failure.message : "브라우저 저장소 접근 실패" }; }
  }, key);
  expect(result.error, "브라우저 저장소를 읽을 수 없습니다.").toBeNull();
  return result.value;
}
