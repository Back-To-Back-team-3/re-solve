"""비공개 시드 등록 도구의 경합·재요청 및 검증 중단을 확인한다."""

import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch

SPEC = importlib.util.spec_from_file_location(
    "materialize", Path(__file__).with_name("materialize-seed-problems.py")
)
seed = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(seed)


def entry(problem_id="1"):
    """비공개 문제 원문과 무관한 최소 입력을 생성한다."""
    return {
        "problemId": problem_id,
        "content": {
            "title": "도구 테스트", "description": "설명", "constraints": "제약", "examples": "예제",
            "difficulty": 1, "tagIds": ["1"], "functionSpec": {},
            "testCases": [{"isSample": True}, {"isSample": False}],
        },
        "solutions": {kind: f"{kind} code" for kind in ("wa", "re", "ac")},
    }


class MaterializeSeedTest(unittest.TestCase):
    """원래 ID·멱등 요청과 실제 검증 결과만 사용하는지 확인한다."""

    def setUp(self):
        self.state = {"batch": "test", "problems": {}}
        self.persist = Mock()
        self.calls = []
        self.version = 1

    def request(self, path, body=None, key=None):
        """기존 목록·새 회차·검증 접수와 현재 회차를 응답한다."""
        self.calls.append((path, body, key))
        if "?page=" in path:
            return {"content": [{
                "problemId": "1", "title": "도구 테스트", "rowVersion": self.version,
                "visibility": "PUBLIC", "scope": "GENERAL",
            }], "hasNext": False}
        if path.endswith("/revisions"):
            return {"problemRevisionId": "31"}
        if path.endswith("/validations"):
            return {"validationId": key.split("-")[-2]}
        return {
            "problemRevisionId": "31", "currentProblemRevisionId": "31",
            "visibility": "PUBLIC", "supportedLanguages": ["java"],
        }

    def progress(self, request, path, expected, deadline):
        """선택한 코드 종류의 테스트별 판정을 반환한다."""
        variant = path.rsplit("/", 1)[-1]
        verdict = "AC" if variant == "ac" else variant.upper()
        return {"status": expected, "resultApplied": True, "tests": [{"verdict": verdict}] * 2}

    def run_materialize(self, catalog=None, attempts=1):
        """HTTP 대기 없이 도구의 재요청 흐름을 실행한다."""
        with patch.object(seed.api, "wait_for_validation", side_effect=self.progress):
            seed.materialize(self.request, catalog or [entry()], self.state, self.persist, attempts)

    def test_unknown_revision_response_reuses_original_version_and_key(self):
        original = self.request
        def interrupted(path, body=None, key=None):
            response = original(path, body, key)
            if path.endswith("/revisions"):
                self.version = 2
                raise TimeoutError("응답 유실")
            return response
        with self.assertRaises(TimeoutError):
            seed.materialize(interrupted, [entry()], self.state, self.persist, 1)
        self.run_materialize()
        writes = [(body, key) for path, body, key in self.calls if path.endswith("/revisions")]
        self.assertEqual(2, len(writes))
        self.assertEqual(writes[0], writes[1])
        self.assertEqual(1, writes[1][0]["rowVersion"])

    def test_completed_resume_does_not_create_revision_or_validation(self):
        self.run_materialize()
        self.calls.clear()
        self.run_materialize()
        self.assertTrue(all(body is None for _, body, _ in self.calls))
        self.assertEqual(["wa", "re", "ac"], self.state["problems"]["1"]["verified"])

    def test_preflight_missing_second_problem_causes_no_writes(self):
        with self.assertRaises(ValueError):
            self.run_materialize([entry(), entry("2")])
        self.assertTrue(all(body is None for _, body, _ in self.calls))

    def test_wrong_verdict_does_not_mark_solution_verified(self):
        with patch.object(seed.api, "wait_for_validation", return_value={
            "status": "REJECTED", "tests": [{"verdict": "CE"}, {"verdict": "WA"}],
        }):
            with self.assertRaises(RuntimeError):
                seed.materialize(self.request, [entry()], self.state, self.persist, 1)
        self.assertNotIn("verified", self.state["problems"]["1"])

    def test_applied_error_is_only_reason_to_advance_validation_key(self):
        calls = []
        def progress(request, path, expected, deadline):
            calls.append(path)
            if len(calls) == 1:
                return {"status": "ERROR", "resultApplied": True}
            return self.progress(request, path, expected, deadline)
        with patch.object(seed.api, "wait_for_validation", side_effect=progress):
            seed.materialize(self.request, [entry()], self.state, self.persist, 2)
        keys = [key for path, _, key in self.calls if path.endswith("/validations")]
        self.assertEqual(["seed-test-1-wa-1", "seed-test-1-wa-2"], keys[:2])

    def test_poll_timeout_resumes_from_saved_validation_id(self):
        with patch.object(seed.api, "wait_for_validation", side_effect=TimeoutError):
            with self.assertRaises(TimeoutError):
                seed.materialize(self.request, [entry()], self.state, self.persist, 2)
        self.run_materialize(attempts=2)
        keys = [key for path, _, key in self.calls if path.endswith("/validations")]
        self.assertEqual(["seed-test-1-wa-1", "seed-test-1-re-1", "seed-test-1-ac-1"], keys)
        self.assertEqual("wa", self.state["problems"]["1"]["validations"]["wa-1"])

    def test_expired_checkpoint_does_not_mutate_server(self):
        self.state["problems"]["1"] = {"rowVersion": 1, "startedAt": 0}
        with self.assertRaises(RuntimeError):
            self.run_materialize()
        self.assertTrue(all(body is None for _, body, _ in self.calls))

    def test_another_current_revision_is_not_silently_published(self):
        original = self.request
        def changed(path, body=None, key=None):
            response = original(path, body, key)
            if "currentProblemRevisionId" in response:
                response["currentProblemRevisionId"] = "99"
            return response
        with patch.object(seed.api, "wait_for_validation", side_effect=self.progress):
            with self.assertRaises(RuntimeError):
                seed.materialize(changed, [entry()], self.state, self.persist, 1)
        self.assertFalse(any(path.endswith("/publication") for path, _, _ in self.calls))

    def test_catalog_reads_all_sources_before_mutation(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            value = entry()
            for kind in value["solutions"]:
                (root / f"{kind}.java").write_text(f"{kind} source")
                value["solutions"][kind] = f"{kind}.java"
            path = root / "catalog.json"
            path.write_text(json.dumps([value]))
            self.assertEqual(["wa", "re", "ac"], list(seed.load_catalog(path)[0]["solutions"]))
            value["content"]["testCases"] = [{"isSample": True}]
            path.write_text(json.dumps([value]))
            with self.assertRaises(ValueError):
                seed.load_catalog(path)


if __name__ == "__main__":
    unittest.main()
