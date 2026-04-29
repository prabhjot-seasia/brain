import { useState } from 'react'
import Box from '@mui/material/Box'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import Paper from '@mui/material/Paper'
import TextField from '@mui/material/TextField'
import Button from '@mui/material/Button'
import Alert from '@mui/material/Alert'
import Chip from '@mui/material/Chip'
import Divider from '@mui/material/Divider'
import CircularProgress from '@mui/material/CircularProgress'
import AutoAwesomeIcon from '@mui/icons-material/AutoAwesome'
import LinearProgress from '@mui/material/LinearProgress'
import {
  brainApi,
  type AutodevSessionDto,
  type AutodevPlanDto,
  type AutodevExecuteDto,
  type MultiRepoPrResultDto,
  type AutodevRepoSpec,
} from '../../api/brainClient'
import { useJobStream } from '../../hooks/useJobStream'
import { startWatchingJob } from '../../components/widgets'

type Stage = 'INTAKE' | 'CLARIFY' | 'PLAN' | 'EXECUTE' | 'PRS'

export default function AutonomousDevPage() {
  const [stage, setStage] = useState<Stage>('INTAKE')
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const [payload, setPayload] = useState('')
  const [session, setSession] = useState<AutodevSessionDto | null>(null)
  const [answers, setAnswers] = useState('')
  const [plan, setPlan] = useState<AutodevPlanDto | null>(null)
  const [execResult, setExecResult] = useState<AutodevExecuteDto | null>(null)
  const [repos, setRepos] = useState<AutodevRepoSpec[]>([])
  const [prResult, setPrResult] = useState<MultiRepoPrResultDto | null>(null)
  const [executeJobId, setExecuteJobId] = useState<string | null>(null)
  const [createPrsJobId, setCreatePrsJobId] = useState<string | null>(null)

  const executeStream = useJobStream(executeJobId, {
    onTerminal: snap => {
      if (snap?.status === 'SUCCEEDED' && snap.result) {
        try {
          setExecResult(JSON.parse(snap.result) as AutodevExecuteDto)
          setStage('PRS')
        } catch { setError('Execute completed but result was unreadable.') }
      } else if (snap?.status === 'FAILED') {
        setError(snap.errorMessage || 'Execute failed')
      }
      setExecuteJobId(null)
    },
  })

  const createPrsStream = useJobStream(createPrsJobId, {
    onTerminal: snap => {
      if (snap?.status === 'SUCCEEDED' && snap.result) {
        try { setPrResult(JSON.parse(snap.result) as MultiRepoPrResultDto) }
        catch { setError('PR creation completed but result was unreadable.') }
      } else if (snap?.status === 'FAILED') {
        setError(snap.errorMessage || 'PR creation failed')
      }
      setCreatePrsJobId(null)
    },
  })

  const start = async () => {
    if (!payload.trim()) {
      setError('Paste a requirement before starting.')
      return
    }
    setLoading(true)
    setError(null)
    try {
      const result = await brainApi.autodevStart(payload, undefined, 'FREE_FORM')
      setSession(result)
      setStage(result.planReady ? 'PLAN' : 'CLARIFY')
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : 'Failed to start autodev session')
    } finally {
      setLoading(false)
    }
  }

  const clarify = async () => {
    if (!session) return
    setLoading(true)
    setError(null)
    try {
      const result = await brainApi.autodevClarify(session.sessionId, answers)
      setSession(result)
      setAnswers('')
      if (result.planReady) setStage('PLAN')
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : 'Clarify failed')
    } finally {
      setLoading(false)
    }
  }

  const generatePlan = async () => {
    if (!session) return
    setLoading(true)
    setError(null)
    try {
      const result = await brainApi.autodevPlan(session.sessionId)
      setPlan(result)
      const proposed = session.proposedAffectedProjects ?? []
      setRepos(proposed.map(p => ({ projectId: p.projectId, repoUrl: '', baseBranch: 'main' })))
      setStage('EXECUTE')
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : 'Plan generation failed')
    } finally {
      setLoading(false)
    }
  }

  const execute = async () => {
    if (!session) return
    setError(null)
    try {
      const job = await brainApi.autodevExecuteAsync(session.sessionId)
      setExecuteJobId(job.jobId)
      startWatchingJob(job.jobId, `Autodev execute ${session.sessionId.slice(0, 8)}`)
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : 'Execute failed')
    }
  }

  const createPrs = async () => {
    if (!session) return
    const ready = repos.filter(r => r.repoUrl.trim())
    if (ready.length === 0) {
      setError('Fill at least one repoUrl before creating PRs.')
      return
    }
    setError(null)
    try {
      const job = await brainApi.autodevCreatePrsAsync(session.sessionId, ready)
      setCreatePrsJobId(job.jobId)
      startWatchingJob(job.jobId, `Autodev create PRs ${session.sessionId.slice(0, 8)}`)
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : 'PR creation failed')
    }
  }

  const reset = () => {
    setStage('INTAKE')
    setPayload('')
    setSession(null)
    setAnswers('')
    setPlan(null)
    setExecResult(null)
    setRepos([])
    setPrResult(null)
    setError(null)
    setExecuteJobId(null)
    setCreatePrsJobId(null)
  }

  const executing = executeStream.snapshot?.status === 'QUEUED' || executeStream.snapshot?.status === 'RUNNING'
  const creatingPrs = createPrsStream.snapshot?.status === 'QUEUED' || createPrsStream.snapshot?.status === 'RUNNING'

  return (
    <Box sx={{ maxWidth: 960, mx: 'auto' }}>
      <Stack direction="row" spacing={1} alignItems="center" mb={2}>
        <AutoAwesomeIcon color="primary" />
        <Typography variant="h5">Autonomous Development</Typography>
      </Stack>
      <Typography variant="body2" color="text.secondary" mb={3}>
        Feed a requirement, review affected repos, approve the multi-repo plan, then fan out
        draft PRs. Each stage has an explicit approval gate.
      </Typography>

      {error && <Alert severity="error" sx={{ mb: 2 }} onClose={() => setError(null)}>{error}</Alert>}

      <Stack spacing={3}>
        <Paper variant="outlined" sx={{ p: 2 }}>
          <Typography variant="subtitle1" fontWeight={600} mb={1}>
            Stage 1 — Intake {stage === 'INTAKE' && <Chip size="small" label="current" color="primary" sx={{ ml: 1 }} />}
          </Typography>
          <TextField
            label="Requirement (paste Jira ticket, spec, or free-form description)"
            aria-label="Requirement input"
            multiline
            minRows={4}
            fullWidth
            value={payload}
            onChange={e => setPayload(e.target.value)}
            disabled={stage !== 'INTAKE' || loading}
          />
          <Box sx={{ mt: 1, display: 'flex', gap: 1 }}>
            {stage === 'INTAKE' ? (
              <Button variant="contained" onClick={start} disabled={loading}>
                {loading ? <CircularProgress size={18} /> : 'Start'}
              </Button>
            ) : (
              <Button onClick={reset}>Reset</Button>
            )}
          </Box>
        </Paper>

        {session && (
          <Paper variant="outlined" sx={{ p: 2 }}>
            <Typography variant="subtitle1" fontWeight={600} mb={1}>
              Stage 2 — Clarification & affinity {stage === 'CLARIFY' && <Chip size="small" label="current" color="primary" sx={{ ml: 1 }} />}
            </Typography>
            {session.proposedAffectedProjects && session.proposedAffectedProjects.length > 0 && (
              <Box sx={{ mb: 2 }}>
                <Typography variant="body2" color="text.secondary">Proposed repos:</Typography>
                <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap sx={{ mt: 0.5 }}>
                  {session.proposedAffectedProjects.map(p => (
                    <Chip key={p.projectId} label={`${p.projectId} · ${Math.round(p.confidence * 100)}%`} />
                  ))}
                </Stack>
              </Box>
            )}
            {session.clarificationQuestions.length > 0 ? (
              <>
                <Typography variant="body2" fontWeight={500} mb={1}>Questions:</Typography>
                <ol style={{ marginTop: 0 }}>
                  {session.clarificationQuestions.map((q, i) => <li key={i}>{q}</li>)}
                </ol>
                <TextField
                  label="Your answers"
                  aria-label="Clarification answers"
                  multiline
                  minRows={3}
                  fullWidth
                  value={answers}
                  onChange={e => setAnswers(e.target.value)}
                  disabled={loading || stage !== 'CLARIFY'}
                  sx={{ mt: 1 }}
                />
                <Box sx={{ mt: 1 }}>
                  <Button variant="contained" onClick={clarify} disabled={loading || stage !== 'CLARIFY'}>
                    Submit answers
                  </Button>
                </Box>
              </>
            ) : (
              <Typography variant="body2" color="success.main">
                Brain is confident. Proceed to plan generation.
              </Typography>
            )}
          </Paper>
        )}

        {session?.planReady && (
          <Paper variant="outlined" sx={{ p: 2 }}>
            <Typography variant="subtitle1" fontWeight={600} mb={1}>
              Stage 3 — Multi-repo plan {stage === 'PLAN' && <Chip size="small" label="current" color="primary" sx={{ ml: 1 }} />}
            </Typography>
            {plan ? (
              <Box component="pre" sx={{
                fontSize: 12, bgcolor: 'grey.100', p: 1.5, borderRadius: 1,
                maxHeight: 320, overflow: 'auto', whiteSpace: 'pre-wrap',
              }}>
                {(() => {
                  try { return JSON.stringify(JSON.parse(plan.multiRepoPlan), null, 2) }
                  catch { return plan.multiRepoPlan }
                })()}
              </Box>
            ) : (
              <Button variant="contained" onClick={generatePlan} disabled={loading || stage !== 'PLAN'}>
                {loading ? <CircularProgress size={18} /> : 'Generate plan'}
              </Button>
            )}
          </Paper>
        )}

        {plan && (
          <Paper variant="outlined" sx={{ p: 2 }}>
            <Typography variant="subtitle1" fontWeight={600} mb={1}>
              Stage 4 — Execute (edit orchestration) {stage === 'EXECUTE' && <Chip size="small" label="current" color="primary" sx={{ ml: 1 }} />}
            </Typography>
            {execResult ? (
              <Stack spacing={0.5}>
                {execResult.perProject.map(p => (
                  <Typography key={p.projectId} variant="body2">
                    <strong>{p.projectId}</strong> — {p.fileCount} file(s), {p.nodeCount} plan node(s)
                    {p.nodeErrors.length > 0 && <span style={{ color: 'orange' }}> · {p.nodeErrors.length} error(s)</span>}
                  </Typography>
                ))}
              </Stack>
            ) : (
              <Box>
                <Button variant="contained" onClick={execute} disabled={loading || executing || stage !== 'EXECUTE'}>
                  {executing ? <CircularProgress size={18} /> : 'Approve & execute'}
                </Button>
                {executing && (
                  <Box sx={{ mt: 1.5 }}>
                    <Typography variant="caption" color="text.secondary">
                      {executeStream.snapshot?.progressMsg ?? 'Orchestrating edits across affected projects…'}
                    </Typography>
                    <LinearProgress
                      sx={{ mt: 0.5 }}
                      variant={executeStream.snapshot?.progressPct != null ? 'determinate' : 'indeterminate'}
                      value={executeStream.snapshot?.progressPct ?? undefined}
                    />
                  </Box>
                )}
              </Box>
            )}
          </Paper>
        )}

        {execResult && (
          <Paper variant="outlined" sx={{ p: 2 }}>
            <Typography variant="subtitle1" fontWeight={600} mb={1}>
              Stage 5 — Draft PRs {stage === 'PRS' && <Chip size="small" label="current" color="primary" sx={{ ml: 1 }} />}
            </Typography>
            {prResult ? (
              <>
                <Typography variant="body2" mb={1}>
                  Batch <code>{prResult.batchId}</code> — <strong>{prResult.overallStatus}</strong>
                  {' '}(succeeded: {prResult.succeeded}, failed: {prResult.failed}, skipped: {prResult.skipped})
                </Typography>
                <Divider sx={{ my: 1 }} />
                <Stack spacing={0.5}>
                  {prResult.perRepo.map(r => (
                    <Typography key={r.projectId} variant="body2">
                      <Chip size="small" label={r.outcome}
                        color={r.outcome === 'SUCCESS' ? 'success' : r.outcome === 'FAILED' ? 'error' : 'default'}
                        sx={{ mr: 1 }} />
                      <strong>{r.projectId}</strong> → {r.prUrl ? <a href={r.prUrl} target="_blank" rel="noopener noreferrer">{r.prUrl}</a> : (r.errorMessage ?? '—')}
                    </Typography>
                  ))}
                </Stack>
              </>
            ) : (
              <>
                <Typography variant="body2" color="text.secondary" mb={1}>
                  Provide a GitHub repo URL and base branch for each affected project:
                </Typography>
                <Stack spacing={1}>
                  {repos.map((r, idx) => (
                    <Stack key={r.projectId} direction="row" spacing={1}>
                      <Chip label={r.projectId} sx={{ minWidth: 120 }} />
                      <TextField
                        size="small"
                        label="Repo URL"
                        aria-label={`Repo URL for ${r.projectId}`}
                        fullWidth
                        value={r.repoUrl}
                        onChange={e => setRepos(prev => prev.map((p, i) => i === idx ? { ...p, repoUrl: e.target.value } : p))}
                        disabled={loading}
                      />
                      <TextField
                        size="small"
                        label="Base branch"
                        aria-label={`Base branch for ${r.projectId}`}
                        value={r.baseBranch}
                        onChange={e => setRepos(prev => prev.map((p, i) => i === idx ? { ...p, baseBranch: e.target.value } : p))}
                        disabled={loading}
                        sx={{ width: 140 }}
                      />
                    </Stack>
                  ))}
                </Stack>
                <Box sx={{ mt: 2 }}>
                  <Button variant="contained" onClick={createPrs} disabled={loading || creatingPrs}>
                    {creatingPrs ? <CircularProgress size={18} /> : 'Create PRs'}
                  </Button>
                  {creatingPrs && (
                    <Box sx={{ mt: 1.5 }}>
                      <Typography variant="caption" color="text.secondary">
                        {createPrsStream.snapshot?.progressMsg ?? 'Fanning out PR creation across repos…'}
                      </Typography>
                      <LinearProgress
                        sx={{ mt: 0.5 }}
                        variant={createPrsStream.snapshot?.progressPct != null ? 'determinate' : 'indeterminate'}
                        value={createPrsStream.snapshot?.progressPct ?? undefined}
                      />
                    </Box>
                  )}
                </Box>
              </>
            )}
          </Paper>
        )}
      </Stack>
    </Box>
  )
}
