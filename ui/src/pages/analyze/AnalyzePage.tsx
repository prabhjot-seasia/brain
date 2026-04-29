import { useEffect, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import MarkdownRenderer from '../../components/shared/MarkdownRenderer'
import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'
import Card from '@mui/material/Card'
import CardContent from '@mui/material/CardContent'
import TextField from '@mui/material/TextField'
import Button from '@mui/material/Button'
import Alert from '@mui/material/Alert'
import Chip from '@mui/material/Chip'
import Divider from '@mui/material/Divider'
import LinearProgress from '@mui/material/LinearProgress'
import MenuItem from '@mui/material/MenuItem'
import Accordion from '@mui/material/Accordion'
import AccordionSummary from '@mui/material/AccordionSummary'
import AccordionDetails from '@mui/material/AccordionDetails'
import ExpandMoreIcon from '@mui/icons-material/ExpandMore'
import CheckCircleIcon from '@mui/icons-material/CheckCircle'
import WarningIcon from '@mui/icons-material/Warning'
import QuestionMarkIcon from '@mui/icons-material/QuestionMark'
import SendIcon from '@mui/icons-material/Send'
import { brainApi, type Project, type AnalyzeResponse } from '../../api/brainClient'

type DimKey = 'why' | 'what' | 'where' | 'how'

const DIM_COLORS: Record<DimKey, string> = {
  why: 'primary.main', what: 'primary.dark', where: 'secondary.main', how: 'primary.dark',
}

export default function AnalyzePage() {
  const [params] = useSearchParams()
  const [projects, setProjects] = useState<Project[]>([])
  const [projectId, setProjectId] = useState(params.get('projectId') ?? '')
  const [requirement, setRequirement] = useState('')
  const [answers, setAnswers] = useState('')
  const [sessionId, setSessionId] = useState<string | undefined>()
  const [loading, setLoading] = useState(false)
  const [result, setResult] = useState<AnalyzeResponse | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [rounds, setRounds] = useState(0)

  useEffect(() => {
    brainApi.listProjects().then(setProjects).catch(() => setError('Failed to load projects.'))
  }, [])

  const submit = async () => {
    if (!requirement) {
      setError('Enter a requirement.')
      return
    }
    setError(null)
    setLoading(true)
    try {
      const res = await brainApi.analyze({ projectId: projectId || undefined, requirement, sessionId, answers: answers || undefined })
      setResult(res)
      setSessionId(res.sessionId)
      setAnswers('')
      if (!res.planReady) setRounds(r => r + 1)
    } catch {
      setError('Analysis failed. Check that the Brain API is running.')
    } finally {
      setLoading(false)
    }
  }

  const reset = () => {
    setResult(null); setSessionId(undefined); setAnswers(''); setRounds(0); setError(null)
  }

  return (
    <Box>
      <Typography variant="h5" sx={{ mb: 3 }}>Analyze Requirement</Typography>

      <Card sx={{ mb: 3 }}>
        <CardContent sx={{ p: { xs: 2, sm: 3 } }}>
          <Typography variant="subtitle2" sx={{ mb: 2, color: 'primary.main', fontWeight: 600 }}>
            Requirement
          </Typography>

          <Box sx={{ display: 'flex', flexDirection: { xs: 'column', sm: 'row' }, gap: 2, mb: 2 }}>
            <TextField
              select size="small" label="Project (optional \u2014 auto-detected if empty)" value={projectId}
              onChange={e => { setProjectId(e.target.value); reset() }}
              sx={{ minWidth: { xs: '100%', sm: 320 } }}
            >
              <MenuItem value=""><em>Auto-detect from requirement</em></MenuItem>
              {projects.map(p => <MenuItem key={p.id} value={p.id}>{p.id}</MenuItem>)}
            </TextField>
          </Box>

          <TextField
            fullWidth multiline rows={4} size="small"
            label="Enter your requirement, Jira ticket description, or free-form task"
            value={requirement}
            onChange={e => setRequirement(e.target.value)}
            disabled={!!sessionId}
            sx={{ mb: 2 }}
          />

          {error && <Alert severity="error" sx={{ mb: 2 }}>{error}</Alert>}
          {loading && <LinearProgress sx={{ mb: 2 }} />}

          <Box sx={{ display: 'flex', justifyContent: 'flex-end' }}>
            {sessionId && <Button onClick={reset} sx={{ mr: 1 }} color="inherit">Start Over</Button>}
            <Button variant="contained" onClick={submit} disabled={loading} endIcon={<SendIcon />}>
              {sessionId ? `Submit Round ${rounds + 1}` : 'Analyze'}
            </Button>
          </Box>
        </CardContent>
      </Card>

      {result?.affectedProjects && result.affectedProjects.length > 0 && !projectId && (
        <Alert severity="info" sx={{ mb: 2 }}>
          Auto-detected project: <strong>{result.affectedProjects[0].projectId}</strong>
          {result.affectedProjects.length > 1 && ` (+${result.affectedProjects.length - 1} more)`}
        </Alert>
      )}

      {result && !result.planReady && (
        <Card sx={{ mb: 3, borderLeft: 4, borderColor: 'secondary.main' }}>
          <CardContent sx={{ p: { xs: 2, sm: 3 } }}>
            <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 2 }}>
              <QuestionMarkIcon color="secondary" />
              <Typography variant="h6">Brain needs clarification — Round {rounds}</Typography>
            </Box>

            {result.dimensions && (
              <Box sx={{ display: 'flex', gap: 1, flexWrap: 'wrap', mb: 2 }}>
                {(Object.entries(result.dimensions) as [DimKey, { score: number; summary: string }][]).map(([key, val]) => (
                  <Chip
                    key={key}
                    label={`${key.toUpperCase()} ${Math.round(val.score * 100)}%`}
                    size="small"
                    sx={{
                      backgroundColor: DIM_COLORS[key] ?? 'primary.main',
                      color: 'primary.contrastText',
                      fontWeight: 600,
                    }}
                  />
                ))}
              </Box>
            )}

            {result.unknownReferences && result.unknownReferences.length > 0 && (
              <Alert severity="warning" icon={<WarningIcon />} sx={{ mb: 2 }}>
                <strong>Unknown references — must resolve before proceeding:</strong>{' '}
                {result.unknownReferences.join(', ')}
              </Alert>
            )}

            <Typography variant="subtitle2" sx={{ mb: 1, fontWeight: 600 }}>Questions:</Typography>
            <Box component="ol" sx={{ pl: 2, mb: 3 }}>
              {result.questions?.map((q, i) => (
                <Box component="li" key={i} sx={{ mb: 1, color: 'text.primary' }}>{q.text}</Box>
              ))}
            </Box>

            <TextField
              fullWidth multiline rows={3} size="small"
              label="Your answers (address all questions above)"
              value={answers}
              onChange={e => setAnswers(e.target.value)}
            />
          </CardContent>
        </Card>
      )}

      {result?.planReady && result.plan && (
        <PlanCard plan={result.plan} onReset={reset} />
      )}
    </Box>
  )
}

function PlanCard({ plan, onReset }: { plan: string; onReset: () => void }) {
  let parsed: Record<string, unknown> | null = null
  try {
    let json = plan.trim()
    if (json.includes('```json')) json = json.substring(json.indexOf('```json') + 7, json.lastIndexOf('```')).trim()
    else if (json.startsWith('```')) json = json.substring(json.indexOf('\n') + 1, json.lastIndexOf('```')).trim()
    parsed = JSON.parse(json)
  } catch { parsed = null }

  return (
    <Card sx={{ borderLeft: 4, borderColor: 'primary.main' }}>
      <CardContent sx={{ p: { xs: 2, sm: 3 } }}>
        <Box sx={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', mb: 2 }}>
          <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
            <CheckCircleIcon color="success" />
            <Typography variant="h6">Implementation Plan Ready</Typography>
          </Box>
          <Button size="small" onClick={onReset}>New Analysis</Button>
        </Box>

        {parsed ? <SmartPlanRenderer plan={parsed} /> : <MarkdownRenderer content={plan} />}
      </CardContent>
    </Card>
  )
}

function findKey(obj: Record<string, unknown>, ...candidates: string[]): unknown {
  for (const key of candidates) {
    if (obj[key] !== undefined) return obj[key]
  }
  return undefined
}

function SmartPlanRenderer({ plan }: { plan: Record<string, unknown> }) {
  const affectedFiles = findKey(plan, 'affectedFiles', 'affected_files', 'files') as Array<Record<string, unknown>> | undefined
  const steps = findKey(plan, 'steps', 'implementation_steps', 'implementationSteps') as Array<Record<string, unknown>> | undefined
  const risks = findKey(plan, 'risks', 'risk', 'concerns') as Array<Record<string, unknown>> | undefined
  const conventions = findKey(plan, 'conventionsApplied', 'conventions_applied', 'conventions') as Array<Record<string, unknown>> | undefined
  const understanding = findKey(plan, 'understanding', 'context', 'summary') as Record<string, string> | string | undefined

  const hasStructuredFields = affectedFiles || steps || risks || conventions

  if (hasStructuredFields) {
    return <StructuredPlan affectedFiles={affectedFiles} steps={steps} risks={risks} conventions={conventions} understanding={understanding} />
  }

  return <GenericJsonRenderer data={plan} />
}

function StructuredPlan({ affectedFiles, steps, risks, conventions, understanding }: {
  affectedFiles?: Array<Record<string, unknown>>
  steps?: Array<Record<string, unknown>>
  risks?: Array<Record<string, unknown>>
  conventions?: Array<Record<string, unknown>>
  understanding?: Record<string, string> | string
}) {
  return (
    <Box>
      {understanding && typeof understanding === 'object' && (
        <Box sx={{ display: 'flex', gap: 1, flexWrap: 'wrap', mb: 2 }}>
          {(Object.entries(understanding) as [string, string][]).map(([k, v]) => (
            <Chip key={k} label={`${k.toUpperCase()}: ${v}`} size="small"
              sx={{ backgroundColor: DIM_COLORS[k as DimKey] ?? 'primary.main', color: 'primary.contrastText', maxWidth: { xs: '100%', sm: 400 } }} />
          ))}
        </Box>
      )}

      {understanding && typeof understanding === 'string' && (
        <Typography variant="body2" sx={{ mb: 2 }}>{understanding}</Typography>
      )}

      {understanding && <Divider sx={{ mb: 2 }} />}

      {affectedFiles && affectedFiles.length > 0 && (
        <Accordion defaultExpanded>
          <AccordionSummary expandIcon={<ExpandMoreIcon />}>
            <Typography fontWeight={600}>Affected Files ({affectedFiles.length})</Typography>
          </AccordionSummary>
          <AccordionDetails>
            {affectedFiles.map((f, i) => {
              const path = (f.path ?? f.file ?? f.name ?? '') as string
              const reason = (f.reason ?? f.description ?? f.rationale ?? '') as string
              const confidence = (f.confidence ?? 0) as number
              return (
                <Box key={i} sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 1, flexWrap: 'wrap' }}>
                  {confidence > 0 && <Chip label={`${Math.round(confidence * 100)}%`} size="small" color={confidence > 0.8 ? 'primary' : 'default'} />}
                  <Typography variant="body2" sx={{ fontFamily: 'monospace', wordBreak: 'break-all' }}>{path}</Typography>
                  {reason && <Typography variant="caption" color="text.secondary">— {reason}</Typography>}
                </Box>
              )
            })}
          </AccordionDetails>
        </Accordion>
      )}

      {steps && steps.length > 0 && (
        <Accordion defaultExpanded>
          <AccordionSummary expandIcon={<ExpandMoreIcon />}>
            <Typography fontWeight={600}>Implementation Steps ({steps.length})</Typography>
          </AccordionSummary>
          <AccordionDetails>
            {steps.map((s, i) => {
              const order = (s.order ?? s.step ?? i + 1) as number
              const desc = (s.description ?? s.detail ?? s.action ?? s.title ?? JSON.stringify(s)) as string
              const convention = (s.convention ?? s.rule ?? '') as string
              return (
                <Box key={i} sx={{ mb: 2, pl: 2, borderLeft: 3, borderColor: 'primary.main' }}>
                  <Typography variant="body2" fontWeight={600}>Step {order}: {desc}</Typography>
                  {convention && <Typography variant="caption" color="secondary.main">{convention}</Typography>}
                </Box>
              )
            })}
          </AccordionDetails>
        </Accordion>
      )}

      {risks && risks.length > 0 && (
        <Accordion>
          <AccordionSummary expandIcon={<ExpandMoreIcon />}>
            <Typography fontWeight={600}>Risks ({risks.length})</Typography>
          </AccordionSummary>
          <AccordionDetails>
            {risks.map((r, i) => {
              const desc = (r.description ?? r.risk ?? r.detail ?? JSON.stringify(r)) as string
              const severity = ((r.severity ?? 'medium') as string).toLowerCase()
              return (
                <Alert key={i} severity={severity === 'high' ? 'error' : severity === 'medium' ? 'warning' : 'info'} sx={{ mb: 1 }}>
                  {desc}
                </Alert>
              )
            })}
          </AccordionDetails>
        </Accordion>
      )}

      {conventions && conventions.length > 0 && (
        <Accordion>
          <AccordionSummary expandIcon={<ExpandMoreIcon />}>
            <Typography fontWeight={600}>Conventions Applied ({conventions.length})</Typography>
          </AccordionSummary>
          <AccordionDetails>
            {conventions.map((c, i) => {
              const rule = (c.rule ?? c.convention ?? c.name ?? JSON.stringify(c)) as string
              const source = (c.source ?? c.file ?? '') as string
              return (
                <Box key={i} sx={{ display: 'flex', gap: 1, mb: 0.5, flexWrap: 'wrap' }}>
                  <Typography variant="body2">{rule}</Typography>
                  {source && <Typography variant="caption" color="text.secondary">[{source}]</Typography>}
                </Box>
              )
            })}
          </AccordionDetails>
        </Accordion>
      )}
    </Box>
  )
}

function GenericJsonRenderer({ data }: { data: Record<string, unknown> }) {
  return (
    <Box>
      {Object.entries(data).map(([key, value]) => (
        <Box key={key} sx={{ mb: 2 }}>
          <Typography variant="subtitle2" sx={{ fontWeight: 600, textTransform: 'capitalize', mb: 0.5 }}>
            {key.replace(/([A-Z])/g, ' $1').replace(/_/g, ' ').trim()}
          </Typography>
          {typeof value === 'string' && <Typography variant="body2">{value}</Typography>}
          {typeof value === 'number' && <Typography variant="body2">{value}</Typography>}
          {typeof value === 'boolean' && <Chip label={value ? 'Yes' : 'No'} size="small" color={value ? 'success' : 'default'} />}
          {Array.isArray(value) && (
            <Box component="ul" sx={{ pl: 2, m: 0 }}>
              {value.map((item, i) => (
                <Box component="li" key={i} sx={{ mb: 0.5 }}>
                  <Typography variant="body2">
                    {typeof item === 'string' ? item : typeof item === 'object' ? Object.values(item as Record<string, unknown>).filter(v => typeof v === 'string').join(' — ') : JSON.stringify(item)}
                  </Typography>
                </Box>
              ))}
            </Box>
          )}
          {value && typeof value === 'object' && !Array.isArray(value) && (
            <Box sx={{ pl: 2 }}>
              {Object.entries(value as Record<string, unknown>).map(([k, v]) => (
                <Typography key={k} variant="body2"><strong>{k}:</strong> {String(v)}</Typography>
              ))}
            </Box>
          )}
        </Box>
      ))}
    </Box>
  )
}

