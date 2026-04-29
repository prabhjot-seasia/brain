export const AVENGER_NAMES = [
  "STARK",
  "HAWKEYE",
  "VISION",
  "WIDOW",
  "HULK",
  "FURY",
  "FORGE",
  "ORACLE",
  "MANTIS",
  "JARVIS",
  "THANOS",
] as const;

export type AvengerName = typeof AVENGER_NAMES[number];

export const AVENGER_DESCRIPTIONS: Record<AvengerName, string> = {
  STARK: "Code correctness review — SOLID, DRY, zero hardcoding, no comments. Uses AST validator + convention checker.",
  HAWKEYE: "Security review — OWASP Top 10, secret detection, prompt injection, PII exposure, LLM-specific threats.",
  VISION: "UI/UX review — responsive (375px floor), MUI compliance, accessibility, theme correctness.",
  WIDOW: "Test quality review — coverage gaps, bogus tests, BDD coverage, test-code design principles.",
  HULK: "Performance review — response time budgets, memory, thread pool sizing, DB query latency.",
  FURY: "Documentation review — accuracy, audience-fit, sync with code, no stale content.",
  FORGE: "Code validation review — AST parse, convention enforcement, hallucination detection, diff correctness.",
  ORACLE: "Token economy review — budget enforcement, cache coverage, prompt optimization, cost tracking.",
  MANTIS: "Learning system review — convention weight trends, pattern reuse, adaptive prompt correctness.",
  JARVIS: "Infrastructure review — CDK, Docker, CI/CD, GitHub Actions, AWS security.",
  THANOS: "Overseer — cross-domain gap detection, convention advisor, separation of concerns.",
};
