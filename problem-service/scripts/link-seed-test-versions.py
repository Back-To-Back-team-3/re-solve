#!/usr/bin/env python3
"""기존 시드 테스트의 S3 객체 버전을 검증하고 신규 DB에 연결한다."""

import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
from tempfile import TemporaryDirectory


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


def connection_sql(rows):
    """검증한 시드만 갱신하고 전부 연결됐을 때 트랜잭션을 커밋한다."""
    values = ",\n".join(
        f"('{key}', '{digest}', {size}, CONVERT(X'{version.encode('utf-8').hex()}' USING utf8mb4))"
        for key, digest, size, version in rows
    )
    return f"""
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
    """대상 버킷을 검증하고 로그인 경로와 DB를 지정한 경우에만 연결한다."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--profile", required=True)
    parser.add_argument("--region", required=True)
    parser.add_argument("--bucket", required=True)
    parser.add_argument("--mysql-login-path")
    parser.add_argument("--database")
    args = parser.parse_args()
    if bool(args.mysql_login_path) != bool(args.database):
        parser.error("--mysql-login-path와 --database는 함께 지정해야 합니다.")

    aws = ["aws", "--profile", args.profile, "--region", args.region, "--output", "json", "--no-cli-pager"]
    try:
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
                input=connection_sql(rows), text=True, check=True,
            )
            print(f"시드 테스트 {len(rows)}개의 DB 버전 참조를 확인했습니다.")
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        parser.exit(1, f"{error}\n")


if __name__ == "__main__":
    main()
