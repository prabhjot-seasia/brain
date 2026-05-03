import axios from 'axios'

const client = axios.create({ baseURL: import.meta.env.VITE_API_BASE_URL ?? '/api/v1' })

export interface Project {
  id: string
  name: string
  language: string
  framework: string
  buildTool: string
  description: string
  lastIngested: string | null
  ingestionStatus?: string
  ingestionError?: string
}

export interface IngestionStatusResponse {
  projectId: string
  status: string
  error: string
}

export interface AnalyzeRequest {
  projectId?: string
  requirement: string
  sessionId?: string
  answers?: string
}

export interface AffectedProject {
  projectId: string
  confidence: number
  rationale: string
}

export interface ClarificationQuestion {
  text: string
  options: string[]
}

export interface AnalyzeResponse {
  sessionId: string
  planReady: boolean
  plan?: string
  questions?: ClarificationQuestion[]
  unknownReferences?: string[]
  dimensions?: Record<string, { score: number; summary: string }>
  forcedAfterMaxRounds?: boolean
  kind?: 'IMPLEMENT' | 'EXPLAIN'
  affectedProjects?: AffectedProject[]
}

export interface Convention {
  id: number
  rule: string
  category: string
  sourceFile: string
  trustWeight: number
  projectId: string
}

export const brainApi = {
  listProjects: () =>
    client.get<Project[]>('/projects').then(r => r.data),

  getProject: (id: string) =>
    client.get<Project>(`/projects/${id}`).then(r => r.data),

  ingest: (request: { projectId: string; projectName: string; repoUrl: string; branch?: string; description?: string }) =>
    client.post<IngestStartResponse>('/projects/ingest', request).then(r => r.data),

  analyze: (req: AnalyzeRequest) =>
    client.post<AnalyzeResponse>('/analyze', req).then(r => r.data),

  getConventions: (projectId: string, category?: string) =>
    client.get<Convention[]>(`/projects/${projectId}/conventions`, {
      params: category ? { category } : {},
    }).then(r => r.data),

  getIngestionStatus: (projectId: string) =>
    client.get<IngestionStatusResponse>(`/projects/${projectId}/status`).then(r => r.data),

  getJiraStatus: (userId?: string) =>
    client.get<{ connected: boolean; siteUrl?: string }>('/auth/jira/status', { params: { userId } }).then(r => r.data),

  getJiraConnectUrl: () =>
    client.get<{ authUrl: string; state: string }>('/auth/jira/connect').then(r => r.data),

  jiraCallback: (code: string, userId?: string) =>
    client.post<{ connected: boolean; siteUrl: string }>('/auth/jira/callback', { code, userId }).then(r => r.data),

  fetchJiraTicket: (issueKey: string, userId?: string) =>
    client.post<IntakeResponse>('/intake/jira', { issueKey, userId }).then(r => r.data),

  uploadDocument: (file: File, userId?: string) => {
    const form = new FormData()
    form.append('file', file)
    if (userId) form.append('userId', userId)
    return client.post<IntakeResponse>('/intake/upload', form, {
      headers: { 'Content-Type': 'multipart/form-data' },
    }).then(r => r.data)
  },

  submitAdhoc: (text: string) =>
    client.post<IntakeResponse>('/intake/adhoc', { text }).then(r => r.data),

  generateDoc: (projectId: string, prompt: string, type: string) =>
    client.post<GenerateDocResponse>('/docs/generate', { projectId, prompt, type }).then(r => r.data),

  listDocs: (projectId: string) =>
    client.get<GeneratedDoc[]>('/docs', { params: { projectId } }).then(r => r.data),

  getDoc: (id: string) =>
    client.get<GeneratedDoc>(`/docs/${id}`).then(r => r.data),

  getDocStatus: (id: string) =>
    client.get<GenerateDocResponse>(`/docs/${id}/status`).then(r => r.data),

  deleteDoc: (id: string) =>
    client.delete(`/docs/${id}`).then(r => r.data),

  proposeTickets: (content: string, projectKey: string, userId?: string) =>
    client.post<TicketProposalResponse>('/jira/tickets/propose', { content, projectKey, userId }).then(r => r.data),

  getProposalStatus: (proposalId: string) =>
    client.get<TicketProposalStatusResponse>(`/jira/tickets/${proposalId}/status`).then(r => r.data),

  createTickets: (proposalId: string, tickets: ProposedTicketDto[], projectKey: string, userId?: string) =>
    client.post<TicketCreationResponse>('/jira/tickets/create', { proposalId, tickets, projectKey, userId }).then(r => r.data),

  listTicketProposals: (userId?: string) =>
    client.get<TicketProposalRecord[]>('/jira/tickets', { params: { userId } }).then(r => r.data),

  createPr: (sessionId: string, repoUrl: string, baseBranch: string) =>
    client.post<PrRecordDto>('/pr/create', { sessionId, repoUrl, baseBranch }).then(r => r.data),

  getPr: (id: string) =>
    client.get<PrRecordDto>(`/pr/${id}`).then(r => r.data),

  listPrs: (sessionId?: string) =>
    client.get<PrRecordDto[]>('/pr', { params: sessionId ? { sessionId } : {} }).then(r => r.data),

  getPrReviews: (id: string) =>
    client.get<CodeReviewIterationDto[]>(`/pr/${id}/reviews`).then(r => r.data),

  getPrRemediations: (id: string) =>
    client.get<CiRemediationAttemptDto[]>(`/pr/${id}/remediations`).then(r => r.data),

  analyzeMergedPr: (prId: string, projectId: string) =>
    client.post<LearningEventDto[]>(`/pr/${prId}/analyze-merge`, null, { params: { projectId } }).then(r => r.data),

  getLearningEvents: (projectId?: string) =>
    client.get<LearningEventDto[]>('/learning/events', { params: projectId ? { projectId } : {} }).then(r => r.data),

  getTokenUsageSummary: (hours?: number) =>
    client.get<TokenUsageSummaryDto>('/monitor/token-usage', { params: { hours: hours ?? 24 } }).then(r => r.data),

  getTokenUsageRecent: (limit?: number) =>
    client.get<TokenUsageRecordDto[]>('/monitor/token-usage/recent', { params: { limit: limit ?? 50 } }).then(r => r.data),

  getAvengerHistory: (avenger: string, projectId: string, limit?: number) =>
    client.get<AvengerReviewDto[]>(`/avengers/${avenger}/history`, { params: { projectId, limit: limit ?? 20 } }).then(r => r.data),

  autodevStart: (payload?: string, intakeId?: string, source?: string, seedProjectId?: string) =>
    client.post<AutodevSessionDto>('/autodev/start', { payload, intakeId, source, seedProjectId }).then(r => r.data),

  autodevClarify: (sessionId: string, answers: string) =>
    client.post<AutodevSessionDto>('/autodev/clarify', { sessionId, answers }).then(r => r.data),

  autodevPlan: (sessionId: string) =>
    client.post<AutodevPlanDto>('/autodev/plan', { sessionId }).then(r => r.data),

  autodevExecute: (sessionId: string, approvedProjectIds?: string[]) =>
    client.post<AutodevExecuteDto>('/autodev/execute', { sessionId, approvedProjectIds }).then(r => r.data),

  autodevExecuteAsync: (sessionId: string, approvedProjectIds?: string[]) =>
    client.post<JobStartDto>('/autodev/execute/start', { sessionId, approvedProjectIds }).then(r => r.data),

  autodevCreatePrs: (sessionId: string, repos: AutodevRepoSpec[]) =>
    client.post<MultiRepoPrResultDto>('/autodev/create-prs', { sessionId, repos }).then(r => r.data),

  autodevCreatePrsAsync: (sessionId: string, repos: AutodevRepoSpec[]) =>
    client.post<JobStartDto>('/autodev/create-prs/start', { sessionId, repos }).then(r => r.data),

  autodevGetBatch: (batchId: string) =>
    client.get<PrBatchDto>(`/autodev/batch/${batchId}`).then(r => r.data),

  getArchitecture: (projectId: string) =>
    client.get<ArchitectureView>(`/projects/${projectId}/architecture`).then(r => r.data),

  getHawkeyeAsff: (projectId: string, limit?: number) =>
    client.get<AsffBatch>(`/avengers/hawkeye/findings.asff`,
      { params: { projectId, limit: limit ?? 50 } }).then(r => r.data),

  installRulePack: (projectId: string, body: RulePackInstallRequest, approvalToken?: string) =>
    client.post<RulePackInstallResult>(`/projects/${projectId}/rule-packs/install`, body, {
      headers: approvalToken ? { 'X-Brain-Approval': approvalToken } : {},
    }).then(r => r.data),

  installRulePackAsync: (projectId: string, body: RulePackInstallRequest, approvalToken?: string) =>
    client.post<JobStartDto>(`/projects/${projectId}/rule-packs/install/start`, body, {
      headers: approvalToken ? { 'X-Brain-Approval': approvalToken } : {},
    }).then(r => r.data),

  uninstallRulePack: (projectId: string, packId: string, version: string, approvalToken?: string) =>
    client.delete<RulePackInstallResult>(`/projects/${projectId}/rule-packs/${packId}`,
      { params: { version },
        headers: approvalToken ? { 'X-Brain-Approval': approvalToken } : {} }).then(r => r.data),

  getOracleBudget: (projectId: string) =>
    client.get<OracleBudget>(`/avengers/oracle/budget/${projectId}`).then(r => r.data),

  generateFullDocs: (projectId: string) =>
    client.post<FullDocStartResponse>(`/docs/full/${projectId}`).then(r => r.data),

  getFullDocStatus: (documentId: string) =>
    client.get<FullDocStatusResponse>(`/docs/full/status/${documentId}`).then(r => r.data),

  retryFailedSections: (documentId: string) =>
    client.post<FullDocStartResponse>(`/docs/${documentId}/retry-failed`).then(r => r.data),

  publishDocToConfluence: (documentId: string, target: { spaceKey: string; parentPageId: string }) =>
    client.post(`/docs/${documentId}/publish/confluence`, target).then(r => r.data),

  listFullDocs: (projectId: string) =>
    client.get<FullDocHistoryRow[]>(`/docs/full`, { params: { projectId } }).then(r => r.data),

  downloadFullDocPdf: (documentId: string) =>
    client.get(`/docs/${documentId}/pdf`, { responseType: 'blob' }).then(r => r.data as Blob),

  downloadTypedPdf: (documentId: string, docType: string) =>
    client.get(`/docs/${documentId}/pdf/${docType}`, { responseType: 'blob' })
        .then(r => r.data as Blob),

  getJob: (jobId: string) =>
    client.get<JobSnapshot>(`/jobs/${jobId}`).then(r => r.data),

  listJobs: (projectId?: string, limit = 20) =>
    client.get<JobSnapshot[]>(`/jobs`, {
      params: projectId ? { projectId, limit } : { limit },
    }).then(r => r.data),
}

export interface FullDocStartResponse {
  documentId: string
  status: 'GENERATING' | 'COMPLETED' | 'PARTIAL' | 'FAILED'
  fromCache?: boolean
  generatedAt?: string
  jobId?: string
  attachedToExisting?: boolean
  streamUrl?: string
}

export interface FullDocStatusResponse {
  id: string
  projectId: string
  status: 'PENDING' | 'GENERATING' | 'COMPLETED' | 'PARTIAL' | 'FAILED'
  sectionResults: Record<string, string>
  generatedAt?: string
  cacheHorizon?: string
  fromCache: boolean
  error?: string
  bundlePdfReady?: boolean
  availablePdfTypes?: ('ARCHITECTURE' | 'SEQUENCE_DIAGRAM' | 'CLASS_DIAGRAM' | 'FLOW_DIAGRAM' | 'EXPLANATION')[]
  jobId?: string
}

export interface JobSnapshot {
  id: string
  jobType: string
  targetKind: string
  targetId: string
  projectId: string | null
  status: 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'PARTIAL' | 'FAILED' | 'CANCELLED'
  progressPct: number | null
  progressMsg: string | null
  errorMessage: string | null
  result: string | null
  startedAt: string | null
  finishedAt: string | null
}

export interface IngestStartResponse {
  jobId: string
  jobType: string
  status: JobSnapshot['status']
  attachedToExisting: boolean
  streamUrl: string
  pollUrl: string
}

export type JobStartDto = IngestStartResponse

export interface FullDocHistoryRow {
  id: string
  status: string
  generatedAt: string | null
  createdAt: string | null
  sizeBytes: number
}

export interface OracleBudget {
  recallK: number
  rerankK: number
  tier: 'HIGH' | 'MEDIUM' | 'LOW' | 'DEFAULT'
  recallTokenCost: number
  rerankTokenCost: number
  groundingTokenCost: number
}

export interface AsffSeverity { Label: string; Normalized: number }
export interface AsffWorkflow { Status: string }
export interface AsffCompliance { Status: string }
export interface AsffFinding {
  SchemaVersion: string
  Id: string
  Title: string
  Description?: string
  Severity: AsffSeverity
  Workflow: AsffWorkflow
  Compliance: AsffCompliance
  CreatedAt?: string
  RecordState?: string
  Resources?: Array<{ Id: string; Type: string }>
  UserDefinedFields?: Array<{ Key: string; Value: string }>
}
export interface AsffBatch { Findings: AsffFinding[] }

export interface RulePackConvention {
  rule: string
  category: string
  trustWeight: number
}
export interface RulePackInstallRequest {
  version: string
  pack: {
    id: string
    version: string
    description: string
    conventions: RulePackConvention[]
  }
}
export interface RulePackInstallResult {
  installed?: number
  removed?: number
  sourceTag: string
}

export interface ServiceEdgeDto {
  id: string
  name: string
  baseUrlTemplate: string | null
  inferredProjectId: string | null
  source: string | null
}

export interface QueueEdgeDto {
  id: string
  queueType: string | null
  name: string
  arn: string | null
  source: string | null
}

export interface EndpointSummaryDto {
  id: string
  path: string
  httpMethod: string | null
  source: string | null
}

export interface ArchitectureView {
  projectId: string
  projectName: string
  kind: string | null
  calledServices: ServiceEdgeDto[]
  publishesTo: QueueEdgeDto[]
  consumesFrom: QueueEdgeDto[]
  exposedEndpoints: EndpointSummaryDto[]
  runtimeEdges: RuntimeEdgeDto[]
  counts: Record<string, number>
}

export interface RuntimeEdgeDto {
  fromService: string
  toService: string
  frequency: number
  p50LatencyMs: number
  p99LatencyMs: number
  errorRate: number
  source: string | null
  sampledFromHours: number
}

export interface GenerateDocResponse {
  id: string
  title: string
  docType: string
  status: string
  contentMd?: string
  error?: string
}

export interface GeneratedDoc {
  id: string
  projectId: string
  title: string
  docType: string
  prompt: string
  contentMd: string
  createdAt: string
  updatedAt: string
}

export interface IntakeResponse {
  intakeId: string
  sourceType: string
  extractedText: string
  charCount: number
  issueKey?: string
  summary?: string
  fileName?: string
}

export interface ProposedTicketDto {
  title: string
  description: string
  acceptanceCriteria: string
  issueType: string
  storyPoints: number
  priority: string
}

export interface TicketProposalResponse {
  proposalId: string
  projectKey: string
  status: string
  jobId: string
  streamUrl: string
}

export interface TicketProposalStatusResponse {
  proposalId: string
  projectKey: string
  status: string
  tickets?: ProposedTicketDto[]
  count?: number
}

export interface TicketCreationResponse {
  projectKey: string
  ticketCount: number
  jobId: string
  streamUrl: string
  attachedToExisting: boolean
}

export interface TicketProposalRecord {
  id: string
  sourceText: string
  jiraProjectKey: string
  proposedTickets: ProposedTicketDto[]
  createdTicketKeys: string[] | null
  status: string
  userId: string
  createdAt: string
}

export interface PrRecordDto {
  id: string
  sessionId: string
  repoUrl: string
  baseBranch: string
  branchName: string | null
  prNumber: number | null
  prUrl: string | null
  status: string
  generatedFiles: Record<string, string> | null
  selfReviewIterations: number
  errorMessage: string | null
  createdAt: string
  updatedAt: string
}

export interface CodeReviewIterationDto {
  id: string
  prRecordId: string
  iterationNumber: number
  verdict: string
  issuesFound: string[] | null
  fixesApplied: string[] | null
  createdAt: string
}

export interface CiRemediationAttemptDto {
  id: string
  prRecordId: string
  workflowRunId: number | null
  attemptNumber: number
  status: string
  failureSummary: string[] | null
  fixesApplied: string[] | null
  commitSha: string | null
  createdAt: string
}

export interface LearningEventDto {
  id: string
  prRecordId: string | null
  projectId: string
  eventType: string
  avenger: string | null
  conventionRule: string | null
  oldWeight: number | null
  newWeight: number | null
  details: Record<string, unknown> | null
  createdAt: string
}

export interface AutodevSessionDto {
  sessionId: string
  intakeText: string
  proposedAffectedProjects: AffectedProject[] | null
  clarificationQuestions: string[]
  planReady: boolean
}

export interface AutodevPlanDto {
  sessionId: string
  multiRepoPlan: string
}

export interface AutodevExecuteDto {
  sessionId: string
  perProject: Array<{
    projectId: string
    fileCount: number
    nodeCount: number
    nodeErrors: string[]
  }>
}

export interface AutodevRepoSpec {
  projectId: string
  repoUrl: string
  baseBranch: string
}

export interface PerRepoPrDto {
  projectId: string
  repoUrl: string
  outcome: 'SUCCESS' | 'FAILED' | 'SKIPPED'
  failureStage: 'PLAN_PARSE' | 'CODE_GEN' | 'SELF_REVIEW' | 'PR_CREATE' | null
  prUrl: string | null
  prNumber: number | null
  prRecordId: string | null
  errorMessage: string | null
}

export interface MultiRepoPrResultDto {
  batchId: string
  overallStatus: 'PENDING' | 'RUNNING' | 'COMPLETED' | 'PARTIAL' | 'FAILED'
  totalRepos: number
  succeeded: number
  failed: number
  skipped: number
  perRepo: PerRepoPrDto[]
}

export interface PrBatchDto {
  id: string
  sessionId: string
  overallStatus: 'PENDING' | 'RUNNING' | 'COMPLETED' | 'PARTIAL' | 'FAILED'
  totalRepos: number
  succeeded: number
  failed: number
  skipped: number
  createdAt: string
  updatedAt: string
}

export interface AvengerReviewDto {
  id: string
  avenger: string
  projectId: string | null
  requestHash: string
  verdict: 'APPROVED' | 'CHANGES_REQUESTED' | 'BLOCKED'
  issues: string[] | null
  summary: string | null
  tokensIn: number
  tokensOut: number
  latencyMs: number
  createdAt: string
}

export interface TokenUsageSummaryDto {
  totalCalls: number
  cacheHits: number
  cacheHitRate: number
  totalInputTokens: number
  totalOutputTokens: number
  totalCost: number
  breakdown: TokenServiceBreakdownDto[]
}

export interface TokenServiceBreakdownDto {
  serviceName: string
  operation: string
  inputTokens: number
  outputTokens: number
  callCount: number
  cost: number
}

export interface TokenUsageRecordDto {
  id: string
  serviceName: string
  operation: string
  projectId: string | null
  inputTokens: number
  outputTokens: number
  cached: boolean
  latencyMs: number
  costEstimate: number
  modelName: string | null
  createdAt: string
}
