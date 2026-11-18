#!/usr/bin/env python3
"""기존 시드 테스트의 S3 객체 버전을 검증하고 신규 DB에 연결한다."""

import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
from tempfile import TemporaryDirectory
from uuid import UUID


MIGRATION = Path(__file__).resolve().parents[1] / (
    "src/main/resources/db/migration/V14__testcase_seed_problem_test_assets.sql"
)
SEED_CREATED_AT = "2026-10-31 00:00:00.000000"
SEED_KEYS = {f"test-cases/{index}.json" for index in range(1, 151)}


def seed_assets():
    """V14에서 버전 연결 대상과 체크섬, 바이트 크기를 읽는다."""
    rows = re.findall(
        r"'(test-cases/[0-9]+\.json)'(?: AS object_key)?, '([0-9a-f]{64})'(?: AS sha256)?, (\d+)",
        MIGRATION.read_text(encoding="utf-8"),
    )
    if len(rows) != len(SEED_KEYS) or {key for key, _, _ in rows} != SEED_KEYS:
        raise ValueError("V14의 시드 테스트 키 150개를 확인할 수 없습니다.")
    return [(key, digest, int(size)) for key, digest, size in rows]


def object_version(aws, bucket, key, digest, size, destination):
    """최신 객체의 버전을 고정하고 그 버전의 실제 바이트를 확인한다."""
    target = ["--bucket", bucket, "--key", key]
    metadata = json.loads(subprocess.check_output(
        [*aws, "s3api", "head-object", *target], text=True,
    ))
    version = metadata.get("VersionId")
    if not isinstance(version, str) or not version or version == "null" or len(version) > 200:
        raise ValueError(f"{key}: 유효한 객체 버전이 없습니다.")
    if metadata.get("ContentLength") != size:
        raise ValueError(f"{key}: 시드 파일 크기가 일치하지 않습니다.")
    subprocess.check_call(
        [*aws, "s3api", "get-object", *target, "--version-id", version, str(destination)],
        stdout=subprocess.DEVNULL,
    )
    content = destination.read_bytes()
    if len(content) != size or hashlib.sha256(content).hexdigest() != digest:
        raise ValueError(f"{key}: 고정 버전의 SHA-256 또는 크기가 일치하지 않습니다.")
    return version


def require_login_path(login_path):
    """기본 로그인 경로로 대체되지 않도록 지정한 그룹의 존재를 확인한다."""
    output = subprocess.check_output(
        ["mysql_config_editor", "print", f"--login-path={login_path}"], text=True,
    )
    if f"[{login_path}]" not in output.splitlines():
        raise ValueError("지정한 MySQL 로그인 경로가 없습니다.")


def connection_sql(rows, database, server_uuid):
    """같은 연결에서 대상 DB를 확인한 후 시드 참조 전체를 연결한다."""
    values = ",\n".join(
        f"('{key}', '{digest}', {size}, CONVERT(X'{version.encode('utf-8').hex()}' USING utf8mb4))"
        for key, digest, size, version in rows
    )
    expected_database = database.encode("utf-8").hex()
    expected_uuid = str(UUID(server_uuid))
    return f"""
CREATE TEMPORARY TABLE seed_test_target_check (
    matched INT NOT NULL CHECK (matched = 1)
) ENGINE=InnoDB;
INSERT INTO seed_test_target_check
SELECT IF(
    BINARY DATABASE() = X'{expected_database}' AND LOWER(@@server_uuid) = '{expected_uuid}',
    1, 0
);

CREATE TEMPORARY TABLE seed_test_versions (
    object_key VARCHAR(500) COLLATE utf8mb4_0900_bin PRIMARY KEY,
    sha256 CHAR(64) COLLATE utf8mb4_0900_bin NOT NULL,
    size_bytes BIGINT NOT NULL,
    object_version VARCHAR(200) COLLATE utf8mb4_0900_bin NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT INTO seed_test_versions VALUES
{values};

CREATE TEMPORARY TABLE seed_test_version_check (
    matched INT NOT NULL CHECK (matched = {len(SEED_KEYS)})
) ENGINE=InnoDB;

START TRANSACTION;
UPDATE problem_test_assets asset
JOIN seed_test_versions seed
    ON asset.object_key = seed.object_key
    AND asset.sha256 = seed.sha256
    AND asset.size_bytes = seed.size_bytes
SET asset.object_version = seed.object_version
WHERE asset.created_at = '{SEED_CREATED_AT}'
    AND asset.status = 'LINKED'
    AND asset.object_version IS NULL;

INSERT INTO seed_test_version_check
SELECT COUNT(*)
FROM problem_test_assets asset
JOIN seed_test_versions seed
    ON asset.object_key = seed.object_key
    AND asset.sha256 = seed.sha256
    AND asset.size_bytes = seed.size_bytes
    AND BINARY asset.object_version = BINARY seed.object_version
WHERE asset.created_at = '{SEED_CREATED_AT}'
    AND asset.status = 'LINKED';
COMMIT;
"""


def main():
    """S3 버전을 검증하고 DB 대상 정보가 모두 지정된 경우에만 연결한다."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--profile", required=True)
    parser.add_argument("--region", required=True)
    parser.add_argument("--bucket", required=True)
    parser.add_argument("--mysql-login-path")
    parser.add_argument("--database")
    parser.add_argument("--mysql-server-uuid")
    args = parser.parse_args()
    db_target = (args.mysql_login_path, args.database, args.mysql_server_uuid)
    if any(value is not None for value in db_target) and not all(db_target):
        parser.error("--mysql-login-path, --database, --mysql-server-uuid는 함께 지정해야 합니다.")

    aws = ["aws", "--profile", args.profile, "--region", args.region, "--output", "json", "--no-cli-pager"]
    try:
        if args.database:
            UUID(args.mysql_server_uuid)
            require_login_path(args.mysql_login_path)
        with TemporaryDirectory() as directory:
            target = Path(directory) / "seed-test.json"
            rows = [
                (key, digest, size, object_version(aws, args.bucket, key, digest, size, target))
                for key, digest, size in seed_assets()
            ]
        print(f"시드 테스트 {len(rows)}개의 S3 버전·SHA-256·크기를 확인했습니다.", flush=True)
        if args.database:
            subprocess.run(
                ["mysql", "--no-defaults", f"--login-path={args.mysql_login_path}",
                 f"--database={args.database}", "--batch", "--skip-force", "--skip-reconnect"],
                input=connection_sql(rows, args.database, args.mysql_server_uuid), text=True, check=True,
            )
            print(f"시드 테스트 {len(rows)}개의 DB 버전 참조를 확인했습니다.")
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        parser.exit(1, f"{error}\n")


if __name__ == "__main__":
    main()
