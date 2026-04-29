export const ROUTES = {
  ROOT: '/',
  PROJECTS: '/projects',
  INGEST: '/ingest',
  ANALYZE: '/analyze',
  AUTODEV: '/autodev',
  DOCS: '/docs',
  TICKETS: '/tickets',
  PRS: '/prs',
  LEARNING: '/learning',
  TOKENS: '/tokens',
  AVENGERS: '/avengers',
  CONVENTIONS: '/conventions',
  ARCHITECTURE: '/architecture',
  SECURITY: '/security',
  RULE_PACKS: '/rule-packs',
  ORACLE: '/oracle',
} as const

export type RoutePath = (typeof ROUTES)[keyof typeof ROUTES]
