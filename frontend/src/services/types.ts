export type Language = "java" | "python3" | "cpp";
export type Verdict = "AC" | "WA" | "CE" | "TLE" | "MLE" | "RE";
export type JudgeState =
  "ACCEPTED" | "QUEUED" | "RUNNING" | "COMPLETED" | "FAILED";
export type Scenario = Verdict | "FAILED";
export type SolvedStatus = "UNSOLVED" | "SOLVED" | "WRONG" | "REVIEW";
export interface Tag {
  id: string;
  name: string;
  pet: string;
  level: number;
  ac: number;
  rate: number;
}
export interface Problem {
  id: string;
  problemRevisionId: string;
  title: string;
  difficulty: number;
  tagId: string;
  rate: number;
  description: string;
  constraints: string;
  examples: string;
  functionSpec: {
    name: "solution";
    parameters: { name: string; type: string }[];
    returnType: string;
  };
  starterCodes: Record<Language, string>;
  samples: { input: string; expected: string }[];
}
export interface Submission {
  id: string;
  userId: string;
  problemId: string;
  problemRevisionId: string;
  examId?: string;
  language: Language;
  sourceCode: string;
  kind: "run" | "submit";
  state: JudgeState;
  verdict: Verdict | null;
  scenario: Scenario;
  createdAt: number;
  finishedAt?: number;
  passedCount: number;
  totalCount: number;
  cpuTimeMs: number;
  memoryKiB: number;
}
export interface User {
  id: string;
  name: string;
  email: string;
  goal: string;
}
export interface Message {
  id: string;
  scope: string;
  userId: string;
  author: string;
  body: string;
  createdAt: number;
  parentId?: string;
}
export interface Study {
  id: string;
  title: string;
  description: string;
  goal: string;
  level: string;
  time: string;
  capacity: number;
  visibility: "public" | "private";
  condition: string;
  tagIds: string[];
  problemIds: string[];
  members: string[];
  applicants: string[];
  ownerId: string;
  deadline: number;
  reason: string;
}
export type ExamStatus = "recruiting" | "scheduled" | "running" | "ended";
export interface Exam {
  id: string;
  kind: "exam" | "contest";
  title: string;
  description: string;
  duration: number;
  status: ExamStatus;
  startsAt: number;
  problemIds: string[];
  participants: string[];
  visibility: "public" | "private";
  condition: string;
  ownerId: string;
}
export interface ExamSession {
  id: string;
  examId: string;
  userId: string;
  startedAt: number;
  endsAt: number;
  finishedAt?: number;
}
export interface Database {
  version: 1;
  seededAt: number;
  currentUserId: string | null;
  users: User[];
  studies: Study[];
  exams: Exam[];
  sessions: ExamSession[];
  submissions: Submission[];
  messages: Message[];
  notes: Record<string, string>;
  scenario: Scenario;
  simulateReadError: boolean;
}
export interface ProblemFilter {
  keyword?: string;
  tag?: string;
  difficulty?: string;
  status?: string;
  sort?: string;
  page?: number;
  size?: number;
}
export interface Page<T> {
  items: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}
