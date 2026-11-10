#!/usr/bin/env python3
"""Register the Java diagnosis golden set through the existing admin APIs."""

import argparse
import getpass
from http.client import IncompleteRead
import json
from pathlib import Path
import time
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener

GOLDEN = Path(__file__).resolve().parents[2] / "judge-service/src/test/resources/golden"
ADMIN_PROBLEMS = "/api/v1/admin/problems"
POLL_INTERVAL_SECONDS = 2
POLL_RETRIES = 3
POLL_RETRY_STATUSES = {429, 502, 503, 504}
REQUEST_TIMEOUT_SECONDS = 15
VALIDATION_TIMEOUT_SECONDS = 180
PROBLEMS = (
    ("p1-sum", "두 정수의 합", "두 정수를 더한 값을 반환하세요."),
    ("p2-array-max", "배열 최대값", "비어 있지 않은 정수 배열의 최대값을 반환하세요."),
    ("p3-palindrome", "팰린드롬 확인", "문자열을 뒤집어도 같으면 true를 반환하세요."),
)


class RejectRedirects(HTTPRedirectHandler):
    """Keep the admin token on the original Gateway request."""

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        """Leave redirects as HTTP errors without issuing another request."""
        return None


HTTP = build_opener(RejectRedirects())


def call(base_url, token, path, body=None, key=None, *, timeout=REQUEST_TIMEOUT_SECONDS):
    """Call the admin API without following redirects or retrying mutations."""
    headers = {"Authorization": f"Bearer {token}"}
    if body is not None:
        headers.update({"Content-Type": "application/json", "Idempotency-Key": key})
    payload = None if body is None else json.dumps(body, ensure_ascii=False).encode("utf-8")
    request = Request(f"{base_url}{path}", data=payload, headers=headers)
    with HTTP.open(request, timeout=timeout) as response:
        return json.load(response)["data"]


def registration_body(tag_id, difficulty, fixture, title, description):
    """Use the original golden inputs and expose only the first case as an example."""
    spec = json.loads((fixture / "spec.json").read_text(encoding="utf-8"))
    cases = json.loads((fixture / "testcases.json").read_text(encoding="utf-8"))
    example = cases[0]
    return {
        "scope": "GENERAL",
        "title": f"[진단 기준] {title}",
        "description": description,
        "constraints": "함수 명세의 자료형을 따릅니다.",
        "examples": f"입력: {json.dumps(example['arguments'], ensure_ascii=False)} / 출력: {example['expected']}",
        "difficulty": difficulty,
        "tagIds": [tag_id],
        "functionSpec": spec,
        "testCases": [
            {"isSample": index == 0, "argumentTexts": case["arguments"], "expectedReturnText": case["expected"]}
            for index, case in enumerate(cases)
        ],
    }


def wait_for_validation(request, progress_path, expected_status, deadline):
    """Retry transient GET failures and wait for Problem to apply a terminal result."""
    failures = 0
    while (remaining := deadline - time.monotonic()) > 0:
        try:
            progress = request(progress_path, timeout=min(REQUEST_TIMEOUT_SECONDS, remaining))
        except (URLError, TimeoutError, ConnectionError, IncompleteRead) as error:
            if isinstance(error, HTTPError):
                if error.code not in POLL_RETRY_STATUSES:
                    raise
                error.close()
            if failures == POLL_RETRIES:
                raise
            failures += 1
        else:
            failures = 0
            status = progress["status"]
            if status in {"PASSED", "REJECTED"} and status != expected_status:
                raise RuntimeError(f"{progress_path}: 예상 {expected_status}, 실제 {status}")
            if status in {expected_status, "ERROR"} and progress["resultApplied"]:
                return progress
        if (remaining := deadline - time.monotonic()) > 0:
            time.sleep(min(POLL_INTERVAL_SECONDS, remaining))
    raise RuntimeError(
        f"{progress_path}: {VALIDATION_TIMEOUT_SECONDS}초 안에 검증이 반영되지 않았습니다. 같은 명령으로 다시 실행하세요."
    )


def verify_solution(request, path, fixture, key, variant, expected_status, expected_test_count, attempts):
    """Advance the stable validation key only after an applied infrastructure error."""
    code = (fixture / f"{variant}.java").read_text(encoding="utf-8")
    first_key = f"{key}-{variant}"
    for attempt in range(1, attempts + 1):
        validation_key = first_key if attempt == 1 else f"{first_key}-retry-{attempt}"
        validation = request(f"{path}/validations", {"language": "java", "sourceCode": code}, validation_key)
        progress_path = f"{ADMIN_PROBLEMS}/validations/{validation['validationId']}"
        deadline = time.monotonic() + VALIDATION_TIMEOUT_SECONDS
        progress = wait_for_validation(request, progress_path, expected_status, deadline)
        if progress["status"] == "ERROR":
            continue
        verdicts = [case["verdict"] for case in progress["tests"]]
        if variant == "ac":
            matches = set(verdicts) == {"AC"}
        else:
            matches = "WA" in verdicts and set(verdicts) <= {"AC", "WA"}
        if len(verdicts) != expected_test_count or not matches:
            raise RuntimeError(f"{fixture.name}/{variant}: 예상과 다른 테스트별 판정 {verdicts}")
        return
    raise RuntimeError(
        f"{fixture.name}/{variant}: 검증이 ERROR로 종결됐습니다. "
        f"Judge를 복구한 뒤 --validation-attempts {attempts + 1} 옵션으로 다시 실행하세요."
    )


def seed(request, tag_id, attempts):
    """Register once per tag and fixture, then verify and publish that exact revision."""
    for difficulty, (slug, title, description) in enumerate(PROBLEMS, start=1):
        key = f"diagnosis-golden-v1-{tag_id}-{slug}"
        fixture = GOLDEN / slug
        body = registration_body(tag_id, difficulty, fixture, title, description)
        registered = request(ADMIN_PROBLEMS, body, f"{key}-create")
        problem_id = registered["problemId"]
        revision_id = registered["problemRevisionId"]
        problem_path = f"{ADMIN_PROBLEMS}/{problem_id}"
        revision_path = f"{problem_path}/revisions/{revision_id}"
        count = len(body["testCases"])
        verify_solution(request, revision_path, fixture, key, "wa", "REJECTED", count, attempts)
        verify_solution(request, revision_path, fixture, key, "ac", "PASSED", count, attempts)

        current = request(problem_path)
        if current["problemRevisionId"] != revision_id or current["currentProblemRevisionId"] != revision_id:
            raise RuntimeError(f"{slug}: 등록한 회차가 현재 사용 회차와 일치하지 않습니다.")
        if current["visibility"] == "PRIVATE":
            request(f"{problem_path}/publication", {"rowVersion": current["rowVersion"]}, f"{key}-publish")
        elif current["visibility"] != "PUBLIC":
            raise RuntimeError(f"{slug}: 공개할 수 없는 상태 {current['visibility']}")
        print(f"난이도 {difficulty}: 문제 {problem_id}, 회차 {revision_id}, WA·AC 검증 완료")


def main():
    """Use a selected test server and tag without storing the admin token."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("base_url", help="테스트 환경의 Gateway 진입 주소")
    parser.add_argument("tag_id", type=int, help="진단 기준 데이터를 연결할 기존 태그 ID")
    parser.add_argument("--validation-attempts", type=int, default=1, help="종결 ERROR까지 포함한 검증 시도 한도")
    args = parser.parse_args()
    if not 0 < args.tag_id < 2**63:
        parser.error("tag_id는 양의 BIGINT여야 합니다.")
    if args.validation_attempts < 1:
        parser.error("--validation-attempts는 1 이상이어야 합니다.")
    base_url = args.base_url.rstrip("/")
    target = urlsplit(base_url)
    is_loopback = target.hostname in {"localhost", "127.0.0.1", "::1"}
    if not target.hostname or (target.scheme != "https" and not (target.scheme == "http" and is_loopback)):
        parser.error("Gateway 주소는 HTTPS 또는 로컬 루프백 HTTP여야 합니다.")
    token = getpass.getpass("관리자 액세스 토큰: ")
    if not token.strip():
        parser.error("관리자 액세스 토큰이 필요합니다.")

    def request(path, body=None, key=None, *, timeout=REQUEST_TIMEOUT_SECONDS):
        """Send one request to the selected gateway."""
        return call(base_url, token, path, body, key, timeout=timeout)

    try:
        seed(request, str(args.tag_id), args.validation_attempts)
    except (RuntimeError, URLError, TimeoutError, ConnectionError, IncompleteRead) as error:
        if isinstance(error, HTTPError):
            error.close()
        parser.exit(1, f"{error}\n")


if __name__ == "__main__":
    main()
