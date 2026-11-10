#!/usr/bin/env python3
"""Register the Java diagnosis golden set through the existing admin APIs."""

import argparse
import getpass
import json
from pathlib import Path
import time
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

GOLDEN = Path(__file__).resolve().parents[2] / "judge-service/src/test/resources/golden"
ADMIN_PROBLEMS = "/api/v1/admin/problems"
PROBLEMS = (
    ("p1-sum", "두 정수의 합", "두 정수를 더한 값을 반환하세요."),
    ("p2-array-max", "배열 최대값", "비어 있지 않은 정수 배열의 최대값을 반환하세요."),
    ("p3-palindrome", "팰린드롬 확인", "문자열을 뒤집어도 같으면 true를 반환하세요."),
)


def call(base_url, token, path, body=None, key=None):
    """Call the admin API with the caller's authorization and optional idempotency key."""
    headers = {"Authorization": f"Bearer {token}"}
    if body is not None:
        headers.update({"Content-Type": "application/json", "Idempotency-Key": key})
    payload = None if body is None else json.dumps(body, ensure_ascii=False).encode("utf-8")
    request = Request(f"{base_url}{path}", data=payload, headers=headers)
    try:
        with urlopen(request, timeout=15) as response:
            return json.load(response)["data"]
    except HTTPError as error:
        raise RuntimeError(f"{request.get_method()} {path}: HTTP {error.code}") from error


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


def verify_solution(request, path, fixture, key, variant, expected_status, expected_test_count):
    """Wait for Judge results and for Problem to apply them before returning."""
    code = (fixture / f"{variant}.java").read_text(encoding="utf-8")
    validation = request(f"{path}/validations", {"language": "java", "sourceCode": code}, f"{key}-{variant}")
    progress_path = f"{ADMIN_PROBLEMS}/validations/{validation['validationId']}"
    deadline = time.monotonic() + 180
    while time.monotonic() < deadline:
        progress = request(progress_path)
        status = progress["status"]
        if status == "ERROR" or (status in {"PASSED", "REJECTED"} and status != expected_status):
            raise RuntimeError(f"{fixture.name}/{variant}: 예상 {expected_status}, 실제 {status}")
        if status == expected_status and progress["resultApplied"]:
            verdicts = [case["verdict"] for case in progress["tests"]]
            if variant == "ac":
                matches = set(verdicts) == {"AC"}
            else:
                matches = "WA" in verdicts and set(verdicts) <= {"AC", "WA"}
            if len(verdicts) != expected_test_count or not matches:
                raise RuntimeError(f"{fixture.name}/{variant}: 예상과 다른 테스트별 판정 {verdicts}")
            return
        time.sleep(2)
    raise RuntimeError(f"{fixture.name}/{variant}: 180초 안에 검증이 반영되지 않았습니다. 같은 명령으로 다시 실행하세요.")


def seed(request, tag_id):
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
        verify_solution(request, revision_path, fixture, key, "wa", "REJECTED", count)
        verify_solution(request, revision_path, fixture, key, "ac", "PASSED", count)

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
    args = parser.parse_args()
    if not 0 < args.tag_id < 2**63:
        parser.error("tag_id는 양의 BIGINT여야 합니다.")
    token = getpass.getpass("관리자 액세스 토큰: ")
    if not token.strip():
        parser.error("관리자 액세스 토큰이 필요합니다.")
    base_url = args.base_url.rstrip("/")

    def request(path, body=None, key=None):
        """Send one request to the selected gateway."""
        return call(base_url, token, path, body, key)

    try:
        seed(request, str(args.tag_id))
    except (RuntimeError, URLError, TimeoutError) as error:
        parser.exit(1, f"{error}\n")


if __name__ == "__main__":
    main()
