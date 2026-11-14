/**
 * 화면 설정을 보관하는 localStorage 키 (frontend/AGENTS.md 13. localStorage keys).
 * 첫 화면 스크립트(서버 컴포넌트)와 헤더·마이페이지(클라이언트 컴포넌트)가 같은 값을 읽어야 하므로 클라이언트 경계 밖에 둔다.
 */
export const THEME_STORAGE_KEY = "re-solve:settings:theme";
