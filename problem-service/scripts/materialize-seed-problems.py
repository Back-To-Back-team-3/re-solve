#!/usr/bin/env python3
"""기존 문제 ID에 비공개 시드 자료를 등록하고 정답·오답 검증을 확인한다."""

import argparse
import fcntl
import getpass
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import time
from urllib.parse import urlsplit

MODULE = importlib.util.spec_from_file_location(
    "diagnosis_seed", Path(__file__).with_name("seed-diagnosis-problems.py")
)
api = importlib.util.module_from_spec(MODULE)
MODULE.loader.exec_module(api)


def digest(value):
    """JSON 값의 결정적인 체크섬을 반환한다."""
    return hashlib.sha256(json.dumps(value, sort_keys=True, ensure_ascii=False).encode()).hexdigest()


def load_catalog(path):
    """서버 변경 전에 전체 자료와 정답·오답 파일을 읽는다."""
    catalog = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(catalog, list) or not catalog:
        raise ValueError("자료는 비어 있지 않은 문제 배열이어야 합니다.")
    seen = set()
    for entry in catalog:
        problem_id = str(entry["problemId"])
        if (not problem_id.isdecimal() or not 0 < int(problem_id) < 2**63
                or str(int(problem_id)) != problem_id or problem_id in seen):
            raise ValueError("문제 ID는 중복 없는 양의 BIGINT여야 합니다.")
        seen.add(problem_id)
        content = entry["content"]
        for field in ("title", "description", "constraints", "examples", "difficulty", "tagIds", "functionSpec", "testCases"):
            if field not in content:
                raise ValueError(f"문제 {problem_id}: {field}가 필요합니다.")
        if "rowVersion" in content or not content["tagIds"]:
            raise ValueError(f"문제 {problem_id}: rowVersion은 자동 조회하며 태그가 필요합니다.")
        samples = sum(case["isSample"] is True for case in content["testCases"])
        if samples == 0 or samples == len(content["testCases"]):
            raise ValueError(f"문제 {problem_id}: 공개 예제와 숨김 테스트가 모두 필요합니다.")
        solutions = entry["solutions"]
        if set(solutions) != {"ac", "wa", "re"}:
            raise ValueError(f"문제 {problem_id}: Java ac·wa·re 파일이 필요합니다.")
        entry["solutions"] = {
            kind: (path.parent / solutions[kind]).read_text(encoding="utf-8") for kind in ("wa", "re", "ac")
        }
        if not all(code.strip() for code in entry["solutions"].values()):
            raise ValueError(f"문제 {problem_id}: 검증 코드가 비어 있습니다.")
    return catalog


def save_state(path, state):
    """토큰·문제 원문 없이 재요청에 필요한 버전과 접수 ID를 원자적으로 저장한다."""
    temporary = path.with_suffix(path.suffix + ".tmp")
    fd = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8") as output:
        json.dump(state, output, ensure_ascii=False, indent=2)
    os.replace(temporary, path)


def summaries(request):
    """본문 파일이 없는 기존 시드도 조회 가능한 관리자 목록을 읽는다."""
    result = {}
    page = 0
    while True:
        response = request(f"{api.ADMIN_PROBLEMS}?page={page}&size=100")
        for item in response["content"]:
            result[str(item["problemId"])] = item
        if not response["hasNext"]:
            return result
        page += 1


def materialize(request, catalog, state, persist, attempts):
    """최초 rowVersion을 보존해 새 회차를 등록하고 실제 검증 결과를 확인한다."""
    existing = summaries(request)
    for entry in catalog:
        problem_id = str(entry["problemId"])
        current = existing.get(problem_id)
        if current is None or current["title"] != entry["content"]["title"]:
            raise ValueError(f"문제 {problem_id}: 기존 문제의 제목이 자료와 일치하지 않습니다.")
        if current["visibility"] != "PUBLIC" or current["scope"] != "GENERAL":
            raise ValueError(f"문제 {problem_id}: GENERAL·PUBLIC 시드만 보완합니다.")

    for entry in catalog:
        problem_id = str(entry["problemId"])
        path = f"{api.ADMIN_PROBLEMS}/{problem_id}"
        saved = state["problems"].get(problem_id)
        if saved is None:
            saved = {"rowVersion": existing[problem_id]["rowVersion"], "startedAt": time.time()}
            state["problems"][problem_id] = saved
            persist()
        if "revisionId" not in saved:
            if time.time() - saved["startedAt"] >= 24 * 60 * 60:
                raise RuntimeError(f"문제 {problem_id}: 멱등 보관 24시간이 지나 자동 재요청을 중단합니다.")
            registered = request(
                f"{path}/revisions", {**entry["content"], "rowVersion": saved["rowVersion"]},
                f"seed-{state['batch']}-{problem_id}-revision",
            )
            saved["revisionId"] = registered["problemRevisionId"]
            persist()
        revision_id = saved["revisionId"]
        for variant, source in entry["solutions"].items():
            if variant in saved.get("verified", []):
                continue
            expected = "PASSED" if variant == "ac" else "REJECTED"
            for attempt in range(1, attempts + 1):
                receipts = saved.setdefault("validations", {})
                receipt_key = f"{variant}-{attempt}"
                if receipt_key not in receipts:
                    if time.time() - saved["startedAt"] >= 24 * 60 * 60:
                        raise RuntimeError(f"문제 {problem_id}: 멱등 보관 기한이 지나 검증 재요청을 중단합니다.")
                    validation = request(
                        f"{path}/revisions/{revision_id}/validations",
                        {"language": "java", "sourceCode": source},
                        f"seed-{state['batch']}-{problem_id}-{variant}-{attempt}",
                    )
                    receipts[receipt_key] = validation["validationId"]
                    persist()
                progress = api.wait_for_validation(
                    request, f"{api.ADMIN_PROBLEMS}/validations/{receipts[receipt_key]}",
                    expected, time.monotonic() + api.VALIDATION_TIMEOUT_SECONDS,
                )
                if progress["status"] == "ERROR":
                    continue
                verdicts = [test["verdict"] for test in progress["tests"]]
                allowed = {"AC", "WA", "RE"} if variant == "re" else {"AC", "WA"}
                valid = set(verdicts) == {"AC"} if variant == "ac" else (
                    variant.upper() in verdicts and set(verdicts) <= allowed
                )
                if len(verdicts) != len(entry["content"]["testCases"]) or not valid:
                    raise RuntimeError(f"문제 {problem_id}/{variant}: 예상과 다른 테스트별 판정 {verdicts}")
                saved.setdefault("verified", []).append(variant)
                persist()
                break
            else:
                raise RuntimeError(f"문제 {problem_id}/{variant}: ERROR 복구 후 --validation-attempts를 늘려 재실행하세요.")
        current = request(path)
        if str(current["problemRevisionId"]) != str(revision_id) or str(current["currentProblemRevisionId"]) != str(revision_id):
            raise RuntimeError(f"문제 {problem_id}: 다른 회차가 등록되었거나 통과 회차가 반영되지 않았습니다.")
        if current["visibility"] != "PUBLIC" or "java" not in current["supportedLanguages"]:
            raise RuntimeError(f"문제 {problem_id}: 공개 상태 또는 Java 실행 프로필이 확인되지 않았습니다.")
        print(f"문제 {problem_id}: 회차 {revision_id}, Java AC·WA·RE 검증 및 현재 회차 확인", flush=True)


def main():
    """외부 비공개 자료와 체크포인트를 사용하고 토큰은 메모리에만 유지한다."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("base_url")
    parser.add_argument("catalog", type=Path, help="Git 밖에 보관하는 비공개 JSON 자료")
    parser.add_argument("checkpoint", type=Path, help="같은 작업 재개에 사용할 외부 체크포인트")
    parser.add_argument("--validation-attempts", type=int, default=1)
    parser.add_argument("--check-only", action="store_true", help="서버 호출 없이 전체 파일을 읽고 형식을 확인")
    args = parser.parse_args()
    target = urlsplit(args.base_url)
    if (not target.hostname or target.username or target.password or target.query or target.fragment
            or target.path not in {"", "/"} or target.scheme not in {"https", "http"}
            or (target.scheme == "http" and target.hostname not in {"localhost", "127.0.0.1", "::1"})):
        parser.error("HTTPS 또는 로컬 루프백 HTTP의 Gateway 원점 주소를 입력하세요.")
    if args.validation_attempts < 1:
        parser.error("--validation-attempts는 1 이상이어야 합니다.")
    repository = Path(__file__).resolve().parents[2]
    if args.catalog.resolve().is_relative_to(repository) or args.checkpoint.resolve().is_relative_to(repository):
        parser.error("비공개 자료와 체크포인트는 Git 저장소 밖에 보관하세요.")
    try:
        catalog = load_catalog(args.catalog)
        if args.check_only:
            print(f"{len(catalog)}개 문제의 입력 파일 확인 완료 (서버 검증 전)")
            return
        token = getpass.getpass("관리자 액세스 토큰: ")
        if not token.strip():
            parser.error("관리자 토큰이 필요합니다.")
        origin = args.base_url.rstrip("/")
        signature = digest({"origin": origin, "catalog": catalog})
        lock_fd = os.open(str(args.checkpoint) + ".lock", os.O_RDWR | os.O_CREAT, 0o600)
        with os.fdopen(lock_fd, "w") as lock:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            state = json.loads(args.checkpoint.read_text()) if args.checkpoint.exists() else {
                "signature": signature, "batch": os.urandom(8).hex(), "problems": {},
            }
            if state["signature"] != signature:
                raise ValueError("자료 또는 서버가 변경되었습니다. 기존 체크포인트를 재사용할 수 없습니다.")
            persist = lambda: save_state(args.checkpoint, state)
            persist()
            def request(path, body=None, key=None, *, timeout=api.REQUEST_TIMEOUT_SECONDS):
                """리다이렉트와 자동 쓰기 재시도 없이 Gateway에 요청한다."""
                return api.call(origin, token, path, body, key, timeout=timeout)
            materialize(request, catalog, state, persist, args.validation_attempts)
    except (OSError, ValueError, KeyError, RuntimeError) as error:
        # HTTP 응답 본문에는 원문·코드가 포함될 수 있어 출력하지 않는다.
        parser.exit(1, f"시드 보완 중단: {error}\n")


if __name__ == "__main__":
    main()
