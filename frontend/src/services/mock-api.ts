import { problems, seed, tags } from "../mocks/fixtures";
import type {
  Database,
  Exam,
  ExamSession,
  Language,
  Problem,
  ProblemFilter,
  Scenario,
  Study,
  Submission,
  User,
} from "./types";
export const DB_KEY = "resolve:mock:v1";
const listeners = new Set<() => void>();
let cached: Database | undefined;
let revision = 0;
export function subscribe(fn: () => void) {
  listeners.add(fn);
  return () => {
    listeners.delete(fn);
  };
}
export function getRevision() {
  return revision;
}
function emit() {
  revision++;
  listeners.forEach((fn) => fn());
}
export function readDB(): Database {
  if (typeof window === "undefined")
    throw new Error("목업 데이터는 브라우저에서만 읽습니다.");
  if (!cached) {
    const raw = localStorage.getItem(DB_KEY);
    if (raw) {
      const data = JSON.parse(raw);
      if (data.version !== 1)
        throw new Error(
          "목업 데이터 버전이 다릅니다. 데모 설정에서 초기화해 주세요.",
        );
      cached = data;
    } else {
      cached = seed();
      localStorage.setItem(DB_KEY, JSON.stringify(cached));
    }
  }
  return cached!;
}
function writeDB(db: Database) {
  localStorage.setItem(DB_KEY, JSON.stringify(db));
  cached = db;
  emit();
}
async function read() {
  await new Promise((r) => setTimeout(r, 100));
  const db = readDB();
  if (db.simulateReadError)
    throw new Error(
      "데모 조회 오류입니다. 데모 설정에서 오류 재현을 꺼 주세요.",
    );
  const snapshot = structuredClone(db);
  snapshot.submissions = snapshot.submissions.map((s) => resolveSubmission(s));
  return snapshot;
}
async function change<T>(fn: (db: Database) => T) {
  const db = structuredClone(readDB());
  const result = fn(db);
  writeDB(db);
  return result;
}
function user(db: Database) {
  const u = db.users.find((x) => x.id === db.currentUserId);
  if (!u) throw new Error("로그인이 필요합니다.");
  return u;
}
export function solvedStatus(
  problemId: string,
  submissions: Submission[],
  userId: string | null,
) {
  const rows = submissions.filter(
    (s) =>
      s.problemId === problemId &&
      s.userId === userId &&
      s.kind === "submit" &&
      !s.examId,
  );
  return rows.some((s) => s.verdict === "AC")
    ? "SOLVED"
    : rows.some((s) => s.state === "COMPLETED")
      ? "WRONG"
      : "UNSOLVED";
}
export function filterProblems(
  list: Problem[],
  filter: ProblemFilter,
  submissions: Submission[],
  userId: string | null,
) {
  const matches = list.filter(
    (p) =>
      (!filter.keyword ||
        `${p.id} ${p.title}`.includes(filter.keyword.trim())) &&
      (!filter.tag || p.tagId === filter.tag) &&
      (!filter.difficulty || p.difficulty === Number(filter.difficulty)) &&
      (!filter.status ||
        solvedStatus(p.id, submissions, userId) === filter.status),
  );
  matches.sort((a, b) =>
    filter.sort === "rate"
      ? b.rate - a.rate
      : filter.sort === "difficulty"
        ? a.difficulty - b.difficulty
        : Number(b.id) - Number(a.id),
  );
  const size = Math.max(1, Math.min(100, filter.size || 5)),
    totalPages = Math.ceil(matches.length / size),
    page = Math.min(
      Math.max(0, Number.isFinite(filter.page) ? Math.floor(filter.page!) : 0),
      Math.max(0, totalPages - 1),
    );
  return {
    items: matches.slice(page * size, (page + 1) * size),
    page,
    size,
    totalElements: matches.length,
    totalPages,
  };
}
export function resolveSubmission(s: Submission, now = Date.now()): Submission {
  const age = now - s.createdAt;
  if (s.state === "COMPLETED" || s.state === "FAILED") return s;
  if (age < 350) return { ...s, state: "ACCEPTED" };
  if (age < 700) return { ...s, state: "QUEUED" };
  if (age < 1300) return { ...s, state: "RUNNING" };
  return {
    ...s,
    state: s.scenario === "FAILED" ? "FAILED" : "COMPLETED",
    verdict: s.scenario === "FAILED" ? null : s.scenario,
    finishedAt: s.createdAt + 1300,
    passedCount:
      s.scenario === "AC"
        ? s.totalCount
        : ["FAILED", "CE", "RE", "MLE"].includes(s.scenario)
          ? 0
          : Math.max(0, s.totalCount - 1),
  };
}
export function remainingSeconds(endsAt: number, now = Date.now()) {
  return Math.max(0, Math.ceil((endsAt - now) / 1000));
}
export const api = {
  snapshot: read,
  async catalog() {
    await read();
    return structuredClone({ problems, tags });
  },
  async problems(filter: ProblemFilter = {}) {
    const db = await read();
    return filterProblems(problems, filter, db.submissions, db.currentUserId);
  },
  async problem(id: string) {
    await read();
    return problems.find((p) => p.id === id) || null;
  },
  async tags() {
    await read();
    return tags;
  },
  async currentUser() {
    const db = await read();
    return db.users.find((x) => x.id === db.currentUserId) || null;
  },
  async signup(email: string, name: string) {
    return change((db) => {
      if (db.users.some((u) => u.email === email))
        throw new Error("이미 사용 중인 이메일입니다.");
      const u: User = {
        id: crypto.randomUUID(),
        email,
        name,
        goal: "매일 한 문제씩",
      };
      db.users.push(u);
      db.currentUserId = u.id;
      return u;
    });
  },
  async login(email: string) {
    return change((db) => {
      let u = db.users.find((x) => x.email === email);
      if (!u) {
        u = {
          id: crypto.randomUUID(),
          email,
          name: email.split("@")[0],
          goal: "매일 한 문제씩",
        };
        db.users.push(u);
      }
      db.currentUserId = u.id;
      return u;
    });
  },
  async logout() {
    return change((db) => {
      db.currentUserId = null;
    });
  },
  async profile(values: Pick<User, "name" | "goal">) {
    return change((db) => Object.assign(user(db), values));
  },
  async configure(
    values: Partial<Pick<Database, "scenario" | "simulateReadError">>,
  ) {
    return change((db) => Object.assign(db, values));
  },
  async reset() {
    const db = seed();
    localStorage.setItem(DB_KEY, JSON.stringify(db));
    cached = db;
    emit();
  },
  async submissions(problemId?: string, examId?: string) {
    const db = await read();
    const list = db.submissions.filter(
      (s) =>
        s.userId === db.currentUserId &&
        (!problemId || s.problemId === problemId) &&
        (examId ? s.examId === examId : !s.examId),
    );
    return list
      .map((s) => resolveSubmission(s))
      .sort((a, b) => b.createdAt - a.createdAt);
  },
  async submit(input: {
    problemId: string;
    language: Language;
    sourceCode: string;
    kind: "run" | "submit";
    examId?: string;
  }) {
    return change((db) => {
      const u = user(db),
        p = problems.find((p) => p.id === input.problemId);
      if (!p) throw new Error("문제를 찾을 수 없습니다.");
      if (input.examId) {
        const session = db.sessions.find(
          (s) => s.examId === input.examId && s.userId === u.id,
        );
        if (!session || session.finishedAt || session.endsAt <= Date.now())
          throw new Error("종료된 시험에는 제출할 수 없습니다.");
      }
      const s: Submission = {
        ...input,
        id: crypto.randomUUID(),
        userId: u.id,
        problemRevisionId: p.problemRevisionId,
        scenario: db.scenario,
        state: "ACCEPTED",
        verdict: null,
        createdAt: Date.now(),
        passedCount: 0,
        totalCount: input.kind === "run" ? p.samples.length : 5,
        cpuTimeMs: 24,
        memoryKiB: 32768,
      };
      db.submissions.push(s);
      return s;
    });
  },
  async settle(id: string) {
    return change((db) => {
      const i = db.submissions.findIndex((s) => s.id === id);
      if (i < 0) throw new Error("제출을 찾을 수 없습니다.");
      db.submissions[i] = resolveSubmission(db.submissions[i]);
      return db.submissions[i];
    });
  },
  async studies() {
    return (await read()).studies;
  },
  async study(id: string) {
    return (await read()).studies.find((s) => s.id === id) || null;
  },
  async createStudy(
    input: Omit<
      Study,
      "id" | "members" | "applicants" | "ownerId" | "deadline" | "reason"
    >,
  ) {
    return change((db) => {
      const u = user(db);
      if (
        input.title.trim().length < 2 ||
        input.description.trim().length < 10 ||
        !input.tagIds.length ||
        input.capacity < 2 ||
        input.capacity > 30
      )
        throw new Error("스터디 필수값을 확인해 주세요.");
      const s: Study = {
        ...input,
        id: crypto.randomUUID(),
        members: [u.id],
        applicants: [],
        ownerId: u.id,
        deadline: Date.now() + 604800000,
        reason: "새롭게 시작한 스터디",
      };
      db.studies.push(s);
      return s;
    });
  },
  async applyStudy(id: string) {
    return change((db) => {
      const u = user(db),
        s = db.studies.find((x) => x.id === id);
      if (!s) throw new Error("스터디를 찾을 수 없습니다.");
      if (s.members.includes(u.id)) throw new Error("이미 참여 중입니다.");
      if (s.applicants.includes(u.id))
        s.applicants = s.applicants.filter((x) => x !== u.id);
      else {
        if (s.members.length >= s.capacity)
          throw new Error("모집이 마감되었습니다.");
        s.applicants.push(u.id);
      }
      return s;
    });
  },
  async exams(kind?: Exam["kind"]) {
    return (await read()).exams.filter((e) => !kind || e.kind === kind);
  },
  async exam(id: string) {
    return (await read()).exams.find((e) => e.id === id) || null;
  },
  async createExam(
    input: Omit<Exam, "id" | "participants" | "ownerId" | "status">,
  ) {
    return change((db) => {
      const u = user(db);
      if (
        input.title.trim().length < 2 ||
        input.description.trim().length < 10 ||
        !input.problemIds.length ||
        !Number.isFinite(input.startsAt) ||
        input.duration < 1 ||
        input.duration > 300
      )
        throw new Error("시험 필수값을 확인해 주세요.");
      const e: Exam = {
        ...input,
        id: crypto.randomUUID(),
        participants: [u.id],
        ownerId: u.id,
        status: "recruiting",
      };
      db.exams.push(e);
      return e;
    });
  },
  async joinExam(id: string) {
    return change((db) => {
      const u = user(db),
        e = db.exams.find((x) => x.id === id);
      if (!e) throw new Error("시험을 찾을 수 없습니다.");
      if (e.status === "ended") throw new Error("종료된 시험입니다.");
      e.participants = e.participants.includes(u.id)
        ? e.participants.filter((x) => x !== u.id)
        : [...e.participants, u.id];
      return e;
    });
  },
  async startExam(id: string) {
    return change((db) => {
      const u = user(db),
        e = db.exams.find((x) => x.id === id);
      if (!e) throw new Error("시험을 찾을 수 없습니다.");
      if (e.status === "ended") throw new Error("종료된 시험입니다.");
      let s = db.sessions.find((x) => x.examId === id && x.userId === u.id);
      if (!s) {
        if (!e.participants.includes(u.id)) e.participants.push(u.id);
        s = {
          id: crypto.randomUUID(),
          examId: id,
          userId: u.id,
          startedAt: Date.now(),
          endsAt: Date.now() + e.duration * 60000,
        };
        db.sessions.push(s);
      }
      return s;
    });
  },
  async session(id: string) {
    const db = await read();
    return (
      db.sessions.find(
        (x) => x.examId === id && x.userId === db.currentUserId,
      ) || null
    );
  },
  async finishExam(
    id: string,
    drafts: {
      problemId: string;
      language: Language;
      sourceCode: string;
    }[] = [],
  ) {
    return change((db) => {
      const u = user(db),
        s = db.sessions.find((x) => x.examId === id && x.userId === u.id);
      if (!s) throw new Error("응시 기록이 없습니다.");
      if (s.finishedAt) return s;
      const exam = db.exams.find((e) => e.id === id)!;
      for (const draft of drafts) {
        const p = problems.find((p) => p.id === draft.problemId);
        if (!p || !exam.problemIds.includes(p.id)) continue;
        const last = db.submissions
          .filter(
            (x) =>
              x.userId === u.id &&
              x.examId === id &&
              x.problemId === p.id &&
              x.kind === "submit",
          )
          .sort((a, b) => b.createdAt - a.createdAt)[0];
        if (
          last?.sourceCode === draft.sourceCode &&
          last.language === draft.language
        )
          continue;
        db.submissions.push({
          id: crypto.randomUUID(),
          userId: u.id,
          examId: id,
          problemId: p.id,
          problemRevisionId: p.problemRevisionId,
          language: draft.language,
          sourceCode: draft.sourceCode,
          kind: "submit",
          state: "ACCEPTED",
          verdict: null,
          scenario: db.scenario,
          createdAt: Date.now(),
          passedCount: 0,
          totalCount: 5,
          cpuTimeMs: 24,
          memoryKiB: 32768,
        });
      }
      s.finishedAt = s.finishedAt || Date.now();
      db.submissions = db.submissions.map((x) =>
        x.examId === id && x.userId === u.id
          ? resolveSubmission(x, Math.max(Date.now(), x.createdAt + 1300))
          : x,
      );
      return s;
    });
  },
  async expireExam(id: string) {
    return change((db) => {
      const u = user(db),
        s = db.sessions.find((x) => x.examId === id && x.userId === u.id);
      if (s) s.endsAt = Date.now();
    });
  },
  async messages(scope: string) {
    return (await read()).messages.filter((m) => m.scope === scope);
  },
  async post(scope: string, body: string, parentId?: string) {
    if (!body.trim()) throw new Error("내용을 입력해 주세요.");
    return change((db) => {
      const u = user(db);
      db.messages.push({
        id: crypto.randomUUID(),
        scope,
        body: body.trim(),
        parentId,
        userId: u.id,
        author: u.name,
        createdAt: Date.now(),
      });
    });
  },
  async note(problemId: string) {
    const db = await read();
    return db.notes[`${db.currentUserId}:${problemId}`] || "";
  },
  async saveNote(problemId: string, body: string) {
    return change((db) => {
      db.notes[`${user(db).id}:${problemId}`] = body;
    });
  },
};
export const labels: Record<string, string> = {
  AC: "정답",
  WA: "오답",
  CE: "컴파일 오류",
  TLE: "시간 초과",
  MLE: "메모리 초과",
  RE: "런타임 오류",
  FAILED: "시스템 오류",
  ACCEPTED: "접수 완료",
  QUEUED: "대기 중",
  RUNNING: "채점 중",
  COMPLETED: "채점 완료",
  SOLVED: "해결",
  WRONG: "오답",
  UNSOLVED: "미도전",
  recruiting: "모집 중",
  scheduled: "진행 예정",
  running: "진행 중",
  ended: "종료됨",
  java: "Java",
  python3: "Python",
  cpp: "C++",
};
export type { ExamSession, Scenario };
