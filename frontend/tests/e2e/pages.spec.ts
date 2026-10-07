import { test, expect } from "@playwright/test";
import { mkdirSync } from "node:fs";
const routes = [
  "/",
  "/dashboard",
  "/problems",
  "/studies",
  "/studies/new",
  "/studies/study-1",
  "/studies/study-2",
  "/studies/study-3",
  "/studies/study-4",
  "/studies/study-5",
  "/studies/study-6",
  "/exams",
  "/exams/new",
  "/exams/exam-1",
  "/exams/exam-2",
  "/exams/exam-3",
  "/exams/exam-4",
  "/exams/exam-6",
  "/exams/exam-7",
  "/contests",
  "/contests/exam-5",
  "/login",
  "/signup",
  "/mypage",
  "/locatepage",
  ...Array.from({ length: 10 }, (_, i) => `/problems/${1001 + i}`),
  ...Array.from({ length: 7 }, (_, i) => `/exams/exam-${i + 1}/result`),
  "/problems/missing",
  "/studies/missing",
  "/exams/missing",
  "/missing",
  "/problems?keyword=없는문제",
];
for (const width of [1440, 390])
  test(`all routes at ${width}px in both themes`, async ({ page }) => {
    test.setTimeout(360000);
    const errors: string[] = [];
    page.on("pageerror", (e) => errors.push(e.message));
    await page.setViewportSize({ width, height: 1000 });
    mkdirSync(`test-results/review-${width}`, { recursive: true });
    for (const theme of ["light", "dark"]) {
      await page.goto("/");
      await page.evaluate((t) => {
        localStorage.setItem("resolve:theme", t);
      }, theme);
      for (const [i, path] of routes.entries()) {
        // Solving uses a desktop layout; mobile layout review covers the other routes.
        if (width === 390 && /^\/problems\/\d+$/.test(path)) continue;
        await page.goto(path);
        await expect(page.getByText("LOADING...", { exact: true })).toHaveCount(
          0,
        );
        await expect(page.locator("main")).toHaveAttribute(
          "data-ready",
          "true",
        );
        if (width === 1440 && /^\/problems\/\d+$/.test(path))
          await expect(
            page.getByRole("textbox", { name: "코드 편집기" }),
          ).toBeVisible();
        await page.evaluate(() => document.fonts.ready);
        await expect(page.locator("html")).toHaveAttribute("data-theme", theme);
        const overflow = await page.evaluate(
          () => document.documentElement.scrollWidth > window.innerWidth + 1,
        );
        expect(overflow, `${path} overflows ${width}`).toBe(false);
        if (width === 390 && (path === "/" || path === "/dashboard")) {
          const title = await page.locator(".hero h1, .hero h2").boundingBox();
          expect(title?.width, `${path} mobile heading width`).toBeGreaterThan(
            250,
          );
          expect(title?.height, `${path} mobile heading height`).toBeLessThan(
            200,
          );
        }
        if (width === 1440 && theme === "light" && i === 0)
          await page.screenshot({ path: "test-results/preview-home.png" });
        await page.screenshot({
          path: `test-results/review-${width}/${theme}-${String(i).padStart(2, "0")}.png`,
          fullPage: true,
        });
      }
    }
    expect(errors).toEqual([]);
  });
