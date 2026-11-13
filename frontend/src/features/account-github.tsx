"use client";
import { useCallback, useEffect, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { integrationApi, type ApiError } from "@/services/api";
import type {
  GithubConnectionInfo,
  GithubSyncJobItem,
  GithubSyncJobStatus,
} from "@/services/types";
import {
  Badge,
  Button,
  Empty,
  ErrorState,
  Loading,
  Section,
  useToast,
} from "@/components/ui";

export const SYNC_JOB_STATUS_FILTERS: ReadonlyArray<{
  value: GithubSyncJobStatus | "ALL";
  label: string;
}> = [
  { value: "ALL", label: "전체" },
  { value: "PENDING", label: "대기 중" },
  { value: "RUNNING", label: "진행 중" },
  { value: "RETRY_WAITING", label: "재시도 대기" },
  { value: "RATE_LIMITED", label: "호출 제한 대기" },
  { value: "SUCCEEDED", label: "완료" },
  { value: "FAILED", label: "실패" },
  { value: "CANCELED", label: "취소됨" },
];

const STATUS_LABELS: Record<GithubSyncJobStatus, string> = {
  PENDING: "대기 중",
  RUNNING: "진행 중",
  RETRY_WAITING: "재시도 대기",
  RATE_LIMITED: "호출 제한 대기",
  SUCCEEDED: "커밋 완료",
  FAILED: "실패",
  CANCELED: "취소됨",
};

const STATUS_TONES: Record<GithubSyncJobStatus, string> = {
  SUCCEEDED: "ok",
  FAILED: "danger",
  RUNNING: "warn",
  PENDING: "warn",
  RETRY_WAITING: "warn",
  RATE_LIMITED: "warn",
  CANCELED: "",
};

export function formatSyncJobStatus(status: GithubSyncJobStatus): string {
  return STATUS_LABELS[status];
}

export function getSyncJobBadgeTone(status: GithubSyncJobStatus): string {
  return STATUS_TONES[status];
}

function getErrorMessage(err: unknown, fallback: string): string {
  const apiErr = err as Partial<ApiError> | undefined;
  return apiErr?.message ?? fallback;
}

export function GithubIntegrationSection() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const toast = useToast();

  const [connection, setConnection] = useState<GithubConnectionInfo | null>(null);
  const [jobs, setJobs] = useState<GithubSyncJobItem[]>([]);
  const [statusFilter, setStatusFilter] = useState<GithubSyncJobStatus | "ALL">("ALL");
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [manualSubmissionId, setManualSubmissionId] = useState("");
  const [installationIdInput, setInstallationIdInput] = useState("");
  const [stateInput, setStateInput] = useState("");

  const loadData = useCallback(
    async (filter: GithubSyncJobStatus | "ALL" = statusFilter) => {
      setLoading(true);
      setLoadError(null);
      try {
        const conn = await integrationApi.getGithubConnection();
        setConnection(conn);
        if (conn.status === "CONNECTED") {
          const page = await integrationApi.getSyncJobs({
            status: filter === "ALL" ? undefined : filter,
            page: 0,
            size: 20,
          });
          setJobs(page.content);
        } else {
          setJobs([]);
        }
      } catch (e: unknown) {
        setLoadError(getErrorMessage(e, "GitHub 연동 정보를 불러오지 못했습니다."));
      } finally {
        setLoading(false);
      }
    },
    [statusFilter],
  );

  useEffect(() => {
    const instId = searchParams.get("installation_id") ?? searchParams.get("installationId");
    const state = searchParams.get("state");
    if (instId && state) {
      setInstallationIdInput(instId);
      setStateInput(state);
    }
    loadData();
  }, [loadData, searchParams]);

  async function runAction(fn: () => Promise<void>, fallbackMessage: string) {
    if (busy) return;
    setError(null);
    setBusy(true);
    try {
      await fn();
    } catch (e: unknown) {
      setError(getErrorMessage(e, fallbackMessage));
    } finally {
      setBusy(false);
    }
  }

  async function handleStartInstall() {
    await runAction(async () => {
      const { installUrl } = await integrationApi.getGithubInstallUrl();
      window.location.href = installUrl;
    }, "GitHub App 설치 주소를 가져오지 못했습니다.");
  }

  async function handleCompleteInstall(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault();
    if (!installationIdInput.trim() || !stateInput.trim()) return;
    await runAction(async () => {
      await integrationApi.installGithub({
        installationId: installationIdInput.trim(),
        state: stateInput.trim(),
      });
      toast("GitHub 저장소 연동이 완료되었습니다.");
      setInstallationIdInput("");
      setStateInput("");
      await loadData();
      router.refresh();
    }, "GitHub 저장소 연동 완료 처리에 실패했습니다.");
  }

  async function handleToggleAutoCommit() {
    if (!connection || connection.status !== "CONNECTED") return;
    await runAction(async () => {
      const res = await integrationApi.updateGithubSettings({
        isAutoCommitEnabled: !connection.isAutoCommitEnabled,
      });
      setConnection({ ...connection, isAutoCommitEnabled: res.isAutoCommitEnabled });
      toast("연동 설정이 변경되었습니다.");
      router.refresh();
    }, "연동 설정을 변경하지 못했습니다.");
  }

  async function handleDisconnect() {
    if (!connection || connection.status !== "CONNECTED") return;
    await runAction(async () => {
      await integrationApi.disconnectGithub();
      setConnection({ status: "NOT_CONNECTED" });
      setJobs([]);
      toast("GitHub 저장소 연동을 해제했습니다.");
      router.refresh();
    }, "GitHub 연동 해제에 실패했습니다.");
  }

  async function handleManualSync(submissionId: string) {
    if (!submissionId.trim()) return;
    await runAction(async () => {
      await integrationApi.requestManualSync(submissionId.trim());
      toast("GitHub 커밋 작업을 등록했습니다.");
      setManualSubmissionId("");
      await loadData();
      router.refresh();
    }, "수동 동기화 요청에 실패했습니다.");
  }

  if (loading) return <Loading />;
  if (loadError || !connection) {
    return (
      <ErrorState
        message={loadError ?? "GitHub 연동 정보를 불러오지 못했습니다."}
        retry={() => loadData()}
      />
    );
  }

  const isConnected = connection.status === "CONNECTED";

  return (
    <div className="stack">
      <Section title="GitHub 저장소 연동">
        <div className="stack">
          <p className="muted">
            연습 문제 정답(AC) 코드를 선택한 GitHub 저장소에 자동으로 커밋합니다.
          </p>
          {error && (
            <p className="text-sm text-danger" role="alert">
              {error}
            </p>
          )}
          {!isConnected ? (
            <div className="stack">
              <div className="card row between">
                <div>
                  <strong>연동된 저장소가 없습니다</strong>
                  <p className="muted">GitHub App에서 저장소 1개를 선택해 연동해 주세요.</p>
                </div>
                <Button type="button" className="primary" disabled={busy} onClick={handleStartInstall}>
                  GitHub App 설치하기
                </Button>
              </div>
              <form className="card form-card stack" onSubmit={handleCompleteInstall}>
                <strong>설치 완료 등록</strong>
                <div className="form-grid">
                  <label className="field">
                    설치 ID (installationId)
                    <input
                      className="input"
                      value={installationIdInput}
                      onChange={(e) => setInstallationIdInput(e.target.value)}
                      placeholder="예: 58291032"
                      required
                    />
                  </label>
                  <label className="field">
                    상태 토큰 (state)
                    <input
                      className="input"
                      value={stateInput}
                      onChange={(e) => setStateInput(e.target.value)}
                      placeholder="설치 URL의 state 값"
                      required
                    />
                  </label>
                </div>
                <Button type="submit" disabled={busy}>연동 완료하기</Button>
              </form>
            </div>
          ) : (
            <div className="card row between">
              <div>
                <div className="row">
                  <strong>
                    {connection.repositoryOwner
                      ? `${connection.repositoryOwner}/${connection.repositoryName}`
                      : connection.repositoryName}
                  </strong>
                  <Badge tone="ok">연동됨</Badge>
                </div>
                {connection.githubLogin && <p className="muted">@{connection.githubLogin}</p>}
              </div>
              <div className="row">
                <Button type="button" disabled={busy} onClick={handleToggleAutoCommit}>
                  {connection.isAutoCommitEnabled ? "자동 커밋 끄기" : "자동 커밋 켜기"}
                </Button>
                <Button type="button" disabled={busy} onClick={handleDisconnect}>
                  연동 해제
                </Button>
              </div>
            </div>
          )}
        </div>
      </Section>

      {isConnected && (
        <Section title="GitHub 동기화 작업 (Sync Jobs)">
          <div className="stack">
            <form
              className="row"
              onSubmit={(e) => {
                e.preventDefault();
                handleManualSync(manualSubmissionId);
              }}
            >
              <input
                className="input"
                value={manualSubmissionId}
                onChange={(e) => setManualSubmissionId(e.target.value)}
                placeholder="수동 커밋할 제출 번호(submissionId)"
                required
              />
              <Button type="submit" className="primary" disabled={busy}>
                수동 동기화 요청
              </Button>
            </form>
            <label className="field">
              상태 필터
              <select
                className="input"
                value={statusFilter}
                onChange={(e) => {
                  const next = e.target.value as GithubSyncJobStatus | "ALL";
                  setStatusFilter(next);
                  loadData(next);
                }}
              >
                {SYNC_JOB_STATUS_FILTERS.map((f) => (
                  <option key={f.value} value={f.value}>{f.label}</option>
                ))}
              </select>
            </label>
            {jobs.length === 0 ? (
              <Empty
                title="동기화 작업 내역이 없습니다"
                description="연습 문제를 맞히거나 수동 동기화를 요청하면 여기에 표시됩니다."
              />
            ) : (
              <div className="stack">
                {jobs.map((job) => (
                  <div key={job.syncJobId} className="card row between">
                    <div>
                      <div className="row">
                        <strong>문제 #{job.problemId} · 제출 #{job.submissionId}</strong>
                        <Badge tone={getSyncJobBadgeTone(job.status)}>
                          {formatSyncJobStatus(job.status)}
                        </Badge>
                      </div>
                      <p className="muted">
                        재시도 {job.retryCount}회
                        {job.commitSha ? ` · 커밋 ${job.commitSha.slice(0, 7)}` : ""}
                        {job.errorMessage ? ` · 사유: ${job.errorMessage}` : ""}
                      </p>
                    </div>
                    {job.status === "FAILED" && (
                      <Button type="button" disabled={busy} onClick={() => handleManualSync(job.submissionId)}>
                        다시 시도
                      </Button>
                    )}
                  </div>
                ))}
              </div>
            )}
          </div>
        </Section>
      )}
    </div>
  );
}
