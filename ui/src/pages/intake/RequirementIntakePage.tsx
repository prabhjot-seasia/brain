import { useEffect, useState } from 'react'
import MarkdownRenderer from '../../components/shared/MarkdownRenderer'
import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'
import Card from '@mui/material/Card'
import CardContent from '@mui/material/CardContent'
import Tabs from '@mui/material/Tabs'
import Tab from '@mui/material/Tab'
import TextField from '@mui/material/TextField'
import Button from '@mui/material/Button'
import Alert from '@mui/material/Alert'
import Chip from '@mui/material/Chip'
import LinearProgress from '@mui/material/LinearProgress'
import MenuItem from '@mui/material/MenuItem'
import Stack from '@mui/material/Stack'
import Divider from '@mui/material/Divider'
import Accordion from '@mui/material/Accordion'
import AccordionSummary from '@mui/material/AccordionSummary'
import AccordionDetails from '@mui/material/AccordionDetails'
import ExpandMoreIcon from '@mui/icons-material/ExpandMore'
import SendIcon from '@mui/icons-material/Send'
import CloudUploadIcon from '@mui/icons-material/CloudUpload'
import LinkIcon from '@mui/icons-material/Link'
import EditNoteIcon from '@mui/icons-material/EditNote'
import CheckCircleIcon from '@mui/icons-material/CheckCircle'
import MergeIcon from '@mui/icons-material/Merge'
import WarningIcon from '@mui/icons-material/Warning'
import { brainApi, type Project, type AnalyzeResponse, type ClarificationQuestion } from '../../api/brainClient'
import { useNavigate } from 'react-router-dom'

type DimKey = 'why' | 'what' | 'where' | 'how'

export default function RequirementIntakePage() {
  const navigate = useNavigate()
  const [tab, setTab] = useState(0)
  const [projects, setProjects] = useState<Project[]>([])
  const [projectId, setProjectId] = useState('')
  const [jiraKey, setJiraKey] = useState('')
  const [adhocText, setAdhocText] = useState('')
  const [extractedText, setExtractedText] = useState('')
  const [extracting, setExtracting] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const [sessionId, setSessionId] = useState<string | undefined>()
  const [currentQuestions, setCurrentQuestions] = useState<ClarificationQuestion[]>([])
  const [selectedAnswers, setSelectedAnswers] = useState<Record<number, string>>({})
  const [customInputs, setCustomInputs] = useState<Record<number, string>>({})
  const [analyzing, setAnalyzing] = useState(false)
  const [result, setResult] = useState<AnalyzeResponse | null>(null)
  const [dimensions, setDimensions] = useState<Record<string, { score: number; summary: string }> | null>(null)
  const [round, setRound] = useState(0)

  useEffect(() => {
    brainApi.listProjects().then(setProjects).catch(() => setError('Failed to load projects.'))
  }, [])

  const selectAnswer = (qIndex: number, answer: string) => {
    setSelectedAnswers(prev => ({ ...prev, [qIndex]: answer }))
    if (answer !== '__custom__') {
      setCustomInputs(prev => { const next = { ...prev }; delete next[qIndex]; return next })
    }
  }

  const handleExtractJira = async () => {
    if (!jiraKey.trim()) return
    setExtracting(true)
    setError(null)
    try {
      const res = await brainApi.fetchJiraTicket(jiraKey.trim())
      setExtractedText(res.extractedText)
    } catch {
      setError('Failed to fetch Jira ticket. Check the key and your Jira connection.')
    } finally {
      setExtracting(false)
    }
  }

  const handleUpload = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0]
    if (!file) return
    setExtracting(true)
    setError(null)
    try {
      const res = await brainApi.uploadDocument(file)
      setExtractedText(res.extractedText)
    } catch {
      setError('Failed to extract document content. Check the file format.')
    } finally {
      setExtracting(false)
    }
  }

  const handleAdhocSubmit = () => {
    if (!adhocText.trim()) return
    setExtractedText(adhocText.trim())
  }

  const startAnalysis = async () => {
    if (!extractedText) {
      setError('Provide a requirement.')
      return
    }
    setError(null)
    setAnalyzing(true)
    try {
      const res = await brainApi.analyze({ projectId: projectId || undefined, requirement: extractedText })
      handleAnalyzeResponse(res)
    } catch {
      setError('Analysis failed. Check that the Brain API is running.')
    } finally {
      setAnalyzing(false)
    }
  }

  const submitAnswers = async () => {
    if (!sessionId) return
    const allAnswered = currentQuestions.every((_, i) => {
      const sel = selectedAnswers[i]
      return sel && (sel !== '__custom__' || customInputs[i]?.trim())
    })
    if (!allAnswered) {
      setError('Please answer all questions before submitting.')
      return
    }
    setError(null)

    const formattedAnswers = currentQuestions.map((q, i) => {
      const sel = selectedAnswers[i]
      const answer = sel === '__custom__' ? `[Custom] ${customInputs[i]?.trim()}` : sel
      return `Q${i + 1}: ${q.text}\nA${i + 1}: ${answer}`
    }).join('\n\n')

    setAnalyzing(true)
    try {
      const res = await brainApi.analyze({ projectId: projectId || undefined, requirement: extractedText, sessionId, answers: formattedAnswers })
      handleAnalyzeResponse(res)
    } catch {
      setError('Failed to continue analysis.')
    } finally {
      setAnalyzing(false)
    }
  }

  const handleAnalyzeResponse = (res: AnalyzeResponse) => {
    setResult(res)
    setSessionId(res.sessionId)
    if (res.dimensions) setDimensions(res.dimensions)

    if (!res.planReady && res.questions) {
      setCurrentQuestions(res.questions)
      setSelectedAnswers({})
      setCustomInputs({})
      setRound(r => r + 1)
    }
  }

  const reset = () => {
    setExtractedText('')
    setSessionId(undefined)
    setCurrentQuestions([])
    setSelectedAnswers({})
    setCustomInputs({})
    setResult(null)
    setDimensions(null)
    setError(null)
    setJiraKey('')
    setAdhocText('')
    setRound(0)
  }

  const inClarification = sessionId && result && !result.planReady

  return (
    <Box>
      <Typography variant="h5" sx={{ mb: 3 }}>Analyze Requirement</Typography>

      {!extractedText && !sessionId && (
        <Card sx={{ mb: 3 }}>
          <CardContent sx={{ p: { xs: 2, sm: 3 } }}>
            <Tabs value={tab} onChange={(_, v) => setTab(v)} sx={{ mb: 2 }}>
              <Tab icon={<LinkIcon />} label="Jira Ticket" iconPosition="start" sx={{ textTransform: 'none' }} />
              <Tab icon={<CloudUploadIcon />} label="Upload Doc" iconPosition="start" sx={{ textTransform: 'none' }} />
              <Tab icon={<EditNoteIcon />} label="Free Text" iconPosition="start" sx={{ textTransform: 'none' }} />
            </Tabs>

            {tab === 0 && (
              <Box sx={{ display: 'flex', flexDirection: { xs: 'column', sm: 'row' }, gap: 2 }}>
                <TextField
                  fullWidth size="small" label="Jira Ticket Key or URL"
                  placeholder="PROJ-123 or https://your-org.atlassian.net/browse/PROJ-123"
                  value={jiraKey} onChange={e => setJiraKey(e.target.value)}
                  onKeyDown={e => e.key === 'Enter' && handleExtractJira()}
                />
                <Button variant="contained" onClick={handleExtractJira} disabled={extracting || !jiraKey.trim()}>
                  Fetch
                </Button>
              </Box>
            )}

            {tab === 1 && (
              <Box>
                <Button variant="outlined" component="label" startIcon={<CloudUploadIcon />} fullWidth
                  sx={{ py: 4, borderStyle: 'dashed' }}>
                  Drop a PDF, DOCX, or image here — or click to browse
                  <input type="file" hidden accept=".pdf,.docx,.doc,.png,.jpg,.jpeg,.txt,.md"
                    onChange={handleUpload} />
                </Button>
              </Box>
            )}

            {tab === 2 && (
              <Box>
                <TextField
                  fullWidth multiline rows={6} size="small"
                  label="Describe your requirement, paste a ticket description, or grooming notes"
                  value={adhocText} onChange={e => setAdhocText(e.target.value)}
                />
                <Box sx={{ display: 'flex', justifyContent: 'flex-end', mt: 2 }}>
                  <Button variant="contained" onClick={handleAdhocSubmit} disabled={!adhocText.trim()}>
                    Use This
                  </Button>
                </Box>
              </Box>
            )}

            {extracting && <LinearProgress sx={{ mt: 2 }} />}
            {error && <Alert severity="error" sx={{ mt: 2 }}>{error}</Alert>}
          </CardContent>
        </Card>
      )}

      {extractedText && !sessionId && (
        <Card sx={{ mb: 3 }}>
          <CardContent sx={{ p: { xs: 2, sm: 3 } }}>
            <Typography variant="subtitle2" sx={{ mb: 1, color: 'primary.main', fontWeight: 600 }}>
              Extracted Requirement — Review & Edit
            </Typography>
            <TextField
              fullWidth multiline rows={6} size="small" value={extractedText}
              onChange={e => setExtractedText(e.target.value)} sx={{ mb: 2 }}
            />
            <Box sx={{ display: 'flex', flexDirection: { xs: 'column', sm: 'row' }, gap: 2, alignItems: { sm: 'center' } }}>
              <TextField select size="small" label="Project (optional — auto-detected if empty)" value={projectId}
                onChange={e => setProjectId(e.target.value)} sx={{ minWidth: { xs: '100%', sm: 320 } }}>
                <MenuItem value=""><em>Auto-detect from requirement</em></MenuItem>
                {projects.map(p => <MenuItem key={p.id} value={p.id}>{p.id} — {p.name}</MenuItem>)}
              </TextField>
              <Box sx={{ display: 'flex', gap: 1, ml: { sm: 'auto' } }}>
                <Button color="inherit" onClick={reset}>Back</Button>
                <Button variant="contained" onClick={startAnalysis} disabled={analyzing}
                  endIcon={<SendIcon />}>
                  Analyze
                </Button>
              </Box>
            </Box>
            {analyzing && <LinearProgress sx={{ mt: 2 }} />}
            {error && <Alert severity="error" sx={{ mt: 2 }}>{error}</Alert>}
          </CardContent>
        </Card>
      )}

      {(inClarification || (result?.planReady)) && (
        <Card sx={{ mb: 3 }}>
          <CardContent sx={{ p: { xs: 2, sm: 3 } }}>
            {dimensions && (
              <Box sx={{ display: 'flex', gap: 1, flexWrap: 'wrap', mb: 2 }}>
                {(Object.entries(dimensions) as [DimKey, { score: number; summary: string }][]).map(([key, val]) => (
                  <Chip key={key} size="small"
                    label={`${key.toUpperCase()} ${Math.round(val.score * 100)}%`}
                    sx={{
                      backgroundColor: val.score >= 0.7 ? 'success.main' : val.score >= 0.4 ? 'secondary.main' : 'error.main',
                      color: 'primary.contrastText', fontWeight: 600,
                    }}
                  />
                ))}
              </Box>
            )}

            <Divider sx={{ mb: 2 }} />

            {inClarification && (
              <Box>
                <Typography variant="subtitle2" sx={{ mb: 2, fontWeight: 600 }}>
                  Round {round} — Select or provide answers:
                </Typography>
                <Stack spacing={2} sx={{ mb: 3 }}>
                  {currentQuestions.map((q, qIdx) => (
                    <Box key={qIdx} sx={{ p: 2, borderRadius: 1, backgroundColor: 'background.default', border: 1, borderColor: selectedAnswers[qIdx] ? 'primary.main' : 'divider' }}>
                      <Typography variant="body2" sx={{ fontWeight: 600, mb: 1.5 }}>
                        {qIdx + 1}. {q.text}
                      </Typography>
                      <Box sx={{ display: 'flex', flexDirection: 'column', gap: 1 }}>
                        {q.options.map((opt, oIdx) => (
                          <Chip key={oIdx} label={opt}
                            variant={selectedAnswers[qIdx] === opt ? 'filled' : 'outlined'}
                            color={selectedAnswers[qIdx] === opt ? 'primary' : 'default'}
                            onClick={() => !analyzing && selectAnswer(qIdx, opt)}
                            icon={selectedAnswers[qIdx] === opt ? <CheckCircleIcon /> : undefined}
                            aria-pressed={selectedAnswers[qIdx] === opt}
                            sx={{ justifyContent: 'flex-start', height: 'auto', py: 0.75, '& .MuiChip-label': { whiteSpace: 'normal' }, opacity: analyzing ? 0.6 : 1 }}
                          />
                        ))}
                        <Chip label="Custom answer..."
                          variant={selectedAnswers[qIdx] === '__custom__' ? 'filled' : 'outlined'}
                          color={selectedAnswers[qIdx] === '__custom__' ? 'secondary' : 'default'}
                          onClick={() => !analyzing && selectAnswer(qIdx, '__custom__')}
                          icon={<EditNoteIcon />}
                          aria-pressed={selectedAnswers[qIdx] === '__custom__'}
                          sx={{ justifyContent: 'flex-start', height: 'auto', py: 0.75, opacity: analyzing ? 0.6 : 1 }}
                        />
                        {selectedAnswers[qIdx] === '__custom__' && (
                          <TextField fullWidth size="small" label="Your answer"
                            value={customInputs[qIdx] ?? ''}
                            onChange={e => setCustomInputs(prev => ({ ...prev, [qIdx]: e.target.value }))}
                            multiline maxRows={3} sx={{ mt: 0.5 }}
                          />
                        )}
                      </Box>
                    </Box>
                  ))}
                </Stack>
                {error && <Alert severity="error" sx={{ mb: 2 }}>{error}</Alert>}
                <Box sx={{ display: 'flex', justifyContent: 'flex-end', gap: 1 }}>
                  <Button color="inherit" onClick={reset}>Start Over</Button>
                  <Button variant="contained" onClick={submitAnswers} disabled={analyzing}
                    endIcon={<SendIcon />}>
                    Submit Answers
                  </Button>
                </Box>
              </Box>
            )}

            {analyzing && <LinearProgress sx={{ mt: 1 }} />}

            {result?.planReady && (
              <Box sx={{ mt: 2 }}>
                <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 2 }}>
                  <CheckCircleIcon color="success" />
                  <Typography variant="h6">Plan Ready</Typography>
                  <Button size="small" variant="contained" startIcon={<MergeIcon />}
                    onClick={() => navigate('/prs')} sx={{ ml: 'auto' }}>
                    Create PR
                  </Button>
                  <Button size="small" onClick={reset}>New Analysis</Button>
                </Box>
                {result.affectedProjects && result.affectedProjects.length > 0 && !projectId && (
                  <Alert severity="info" sx={{ mb: 2 }}>
                    Auto-detected project: <strong>{result.affectedProjects[0].projectId}</strong>
                    {result.affectedProjects.length > 1 && ` (+${result.affectedProjects.length - 1} more)`}
                  </Alert>
                )}
                <PlanRenderer plan={result.plan ?? ''} />
              </Box>
            )}
          </CardContent>
        </Card>
      )}
    </Box>
  )
}

function findKey(obj: Record<string, unknown>, ...candidates: string[]): unknown {
  for (const key of candidates) {
    if (obj[key] !== undefined) return obj[key]
  }
  return undefined
}

function PlanRenderer({ plan }: { plan: string }) {
  let parsed: Record<string, unknown> | null = null
  try {
    let json = plan.trim()
    if (json.includes('```json')) json = json.substring(json.indexOf('```json') + 7, json.lastIndexOf('```')).trim()
    else if (json.startsWith('```')) json = json.substring(json.indexOf('\n') + 1, json.lastIndexOf('```')).trim()
    parsed = JSON.parse(json)
  } catch { parsed = null }

  if (parsed) {
    const affectedFiles = findKey(parsed, 'affectedFiles', 'affected_files', 'files') as Array<Record<string, unknown>> | undefined
    const steps = findKey(parsed, 'steps', 'implementation_steps', 'implementationSteps') as Array<Record<string, unknown>> | undefined
    const risks = findKey(parsed, 'risks', 'risk', 'concerns') as Array<Record<string, unknown>> | undefined
    const conventions = findKey(parsed, 'conventionsApplied', 'conventions_applied', 'conventions') as Array<Record<string, unknown>> | undefined

    if (affectedFiles || steps || risks || conventions) {
      return (
        <Box>
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
                    <Alert key={i} icon={<WarningIcon />}
                      severity={severity === 'high' ? 'error' : severity === 'medium' ? 'warning' : 'info'} sx={{ mb: 1 }}>
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

    return (
      <Box>
        {Object.entries(parsed).map(([key, value]) => (
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

  return <MarkdownRenderer content={plan} />
}
