"""시드 버전 연결 도구의 대상과 객체 검증을 확인한다."""

import hashlib
import importlib.util
import json
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import patch


SPEC = importlib.util.spec_from_file_location(
    "link_seed_test_versions", Path(__file__).with_name("link-seed-test-versions.py")
)
seed = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(seed)
SERVER_UUID = "103dfab0-98e1-11ef-8201-0242ac120002"


class LinkSeedTestVersionsTest(unittest.TestCase):
    """S3 응답 대역과 생성된 SQL로 연결 범위를 확인한다."""

    def test_reads_the_exact_seed_set(self):
        """마이그레이션에서 중복 없이 시드 키 150개를 읽는다."""
        rows = seed.seed_assets()
        self.assertEqual(len(rows), 150)
        self.assertEqual({key for key, _, _ in rows}, seed.SEED_KEYS)

    def test_downloads_the_pinned_version(self):
        """HEAD에서 얻은 버전으로 다운로드하고 실제 바이트를 검증한다."""
        content = b'{"args":[1]}'
        digest = hashlib.sha256(content).hexdigest()
        with TemporaryDirectory() as directory:
            destination = Path(directory) / "test.json"
            destination.write_bytes(content)
            with patch.object(seed.subprocess, "check_output", return_value=json.dumps({
                "VersionId": "version-1", "ContentLength": len(content),
            })), patch.object(seed.subprocess, "check_call") as download:
                self.assertEqual(seed.object_version(
                    ["aws"], "bucket", "test-cases/1.json", digest, len(content), destination,
                ), "version-1")
            self.assertEqual(download.call_args.args[0][-3:], ["--version-id", "version-1", str(destination)])

    def test_rejects_missing_versions_and_different_content(self):
        """미버전 객체와 크기가 같아도 내용이 다른 객체는 연결하지 않는다."""
        with TemporaryDirectory() as directory:
            destination = Path(directory) / "test.json"
            destination.write_bytes(b"bad")
            for metadata, message in (
                ({"VersionId": "null", "ContentLength": 3}, "유효한 객체 버전"),
                ({"VersionId": "v1", "ContentLength": 4}, "시드 파일 크기"),
                ({"VersionId": "v1", "ContentLength": 3}, "SHA-256"),
            ):
                with self.subTest(metadata=metadata), patch.object(
                    seed.subprocess, "check_output", return_value=json.dumps(metadata),
                ), patch.object(seed.subprocess, "check_call"), self.assertRaisesRegex(ValueError, message):
                    seed.object_version(["aws"], "bucket", "test-cases/1.json", "0" * 64, 3, destination)

    def test_checks_all_rows_before_commit_without_overwriting_versions(self):
        """누락된 참조만 수정하고 전체 연결 확인이 커밋보다 앞선다."""
        version = "a'\\b"
        sql = seed.connection_sql([("test-cases/1.json", "0" * 64, 3, version)], "problem_db", SERVER_UUID)
        self.assertIn("asset.object_version IS NULL", sql)
        self.assertIn(f"X'{version.encode().hex()}'", sql)
        self.assertNotIn(version, sql)
        self.assertIn("CHECK (matched = 150)", sql)
        self.assertLess(sql.index("INSERT INTO seed_test_version_check"), sql.index("COMMIT"))

    def test_login_path_must_be_the_named_group(self):
        """기본 그룹만 보이는 경우에는 지정한 경로로 연결하지 않는다."""
        with patch.object(seed.subprocess, "check_output", return_value="[client]\nuser = dev\n") as editor:
            with self.assertRaisesRegex(ValueError, "지정한 MySQL 로그인 경로"):
                seed.require_login_path("problem-target")
            editor.assert_called_once_with(
                ["mysql_config_editor", "print", "--login-path=problem-target"], text=True,
            )
        with patch.object(seed.subprocess, "check_output", return_value="[problem-target]\nuser = operator\n"):
            seed.require_login_path("problem-target")

    def test_sql_checks_the_write_session_before_updates(self):
        """대상 DB 이름과 서버 UUID 검증이 시드 갱신보다 앞선다."""
        sql = seed.connection_sql([("test-cases/1.json", "0" * 64, 3, "version-1")], "problem_db", SERVER_UUID)
        self.assertIn(f"BINARY DATABASE() = X'{'problem_db'.encode().hex()}'", sql)
        self.assertIn(f"LOWER(@@server_uuid) = '{SERVER_UUID}'", sql)
        self.assertIn("CHECK (matched = 1)", sql)
        self.assertLess(sql.index("INSERT INTO seed_test_target_check"), sql.index("UPDATE problem_test_assets"))
