import { useEffect, useState } from 'react'
import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'
import Card from '@mui/material/Card'
import CardContent from '@mui/material/CardContent'
import Chip from '@mui/material/Chip'
import Button from '@mui/material/Button'
import Alert from '@mui/material/Alert'
import LinearProgress from '@mui/material/LinearProgress'
import TextField from '@mui/material/TextField'
import Dialog from '@mui/material/Dialog'
import DialogTitle from '@mui/material/DialogTitle'
import DialogContent from '@mui/material/DialogContent'
import DialogActions from '@mui/material/DialogActions'
import Stack from '@mui/material/Stack'
import Divider from '@mui/material/Divider'
import OpenInNewIcon from '@mui/icons-material/OpenInNew'
import AddIcon from '@mui/icons-material/Add'
import ExpandMoreIcon from '@mui/icons-material/ExpandMore'
import Accordion from '@mui/material/Accordion'
import AccordionSummary from '@mui/material/AccordionSummary'
import AccordionDetails from '@mui/material/AccordionDetails'
import { brainApi, type PrRecordDto, type CodeReviewIterationDto, type CiRemediationAttemptDto } from '../../api/brainClient'

const STATUS_COLORS: Record<string, 'default' | 'info' | 'warning' | 'success' | 'error'> = {
  GENERATING: 'info',
  REVIEWING: 'warning',
  CREATING: 'info',
  CREATED: 'success',
  FAILED: 'error',
}

export default function PrStatusPage() {
  const [prs, setPrs] = useState<PrRecordDto[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [dialogOpen, setDialogOpen] = useState(false)
  const [sessionId, setSessionId] = useState('')
  const [repoUrl, setRepoUrl] = useState('')
  const [baseBranch, setBaseBranch] = useState('main')
  const [creating, setCreating] = useState(false)
  const [expandedReviews, setExpandedReviews] = useState<Record<string, CodeReviewIterationDto[]>>({})
  const [expandedRemediations, setExpandedRemediations] = useState<Record<string, CiRemediationAttemptDto[]>>({})

  const loadPrs = () => {
    setLoading(true)
    brainApi.listPrs()
      .then(setPrs)
      .catch(() => setError('Failed to load pull requests.'))
      .finally(() => setLoading(false))
  }

  useEffect(() => { loadPrs() }, [])

  const handleCreate = async () => {
    if (!sessionId.trim() || !repoUrl.trim() || !baseBranch.trim()) return
    setCreating(true)
    setError(null)
    try {
      await brainApi.createPr(sessionId.trim(), repoUrl.trim(), baseBranch.trim())
      setDialogOpen(false)
      setSessionId('')
      setRepoUrl('')
      setBaseBranch('main')
      loadPrs()
    } catch {
      setError('Failed to create PR. Verify the session is COMPLETE and the repo URL is valid.')
    } finally {
      setCreating(false)
    }
  }

  const loadReviews = async (prId: string) => {
    if (expandedReviews[prId]) return
    try {
      const reviews = await brainApi.getPrReviews(prId)
      setExpandedReviews(prev => ({ ...prev, [prId]: reviews }))
    } catch {
      setError('Failed to load review iterations.')
    }
  }

  const loadRemediations = async (prId: string) => {
    if (expandedRemediations[prId]) return
    try {
      const remediations = await brainApi.getPrRemediations(prId)
      setExpandedRemediations(prev => ({ ...prev, [prId]: remediations }))
    } catch {
      setError('Failed to load CI remediation history.')
    }
  }

  return (
    <Box>
      <Box sx={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', mb: 3 }}>
        <Typography variant="h5">Pull Requests</Typography>
        <Button variant="contained" startIcon={<AddIcon />} onClick={() => setDialogOpen(true)}>
          Create PR
        </Button>
      </Box>

      {error && <Alert severity="error" sx={{ mb: 2 }} onClose={() => setError(null)}>{error}</Alert>}
      {loading && <LinearProgress sx={{ mb: 2 }} />}

      {!loading && prs.length === 0 && (
        <Alert severity="info">
          No pull requests yet. Complete an analysis session, then create a PR from the plan.
        </Alert>
      )}

      <Stack spacing={2}>
        {prs.map(pr => (
          <Card key={pr.id}>
            <CardContent sx={{ p: { xs: 2, sm: 3 } }}>
              <Box sx={{ display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: 1, mb: 1.5 }}>
                <Chip label={pr.status} color={STATUS_COLORS[pr.status] ?? 'default'} size="small" />
                {pr.prNumber && (
                  <Typography variant="subtitle2" sx={{ fontWeight: 600 }}>
                    #{pr.prNumber}
                  </Typography>
                )}
                <Typography variant="body2" color="text.secondary" sx={{ ml: 'auto' }}>
                  {new Date(pr.createdAt).toLocaleString()}
                </Typography>
              </Box>

              <Typography variant="body2" sx={{ mb: 0.5 }}>
                <strong>Repo:</strong> {pr.repoUrl}
              </Typography>
              <Typography variant="body2" sx={{ mb: 0.5 }}>
                <strong>Base:</strong> {pr.baseBranch}
                {pr.branchName && <> &rarr; <strong>Branch:</strong> {pr.branchName}</>}
              </Typography>
              <Typography variant="body2" sx={{ mb: 1 }}>
                <strong>Self-review iterations:</strong> {pr.selfReviewIterations}
              </Typography>

              {pr.errorMessage && (
                <Alert severity="error" sx={{ mb: 1 }}>{pr.errorMessage}</Alert>
              )}

              <Box sx={{ display: 'flex', flexWrap: 'wrap', gap: 1 }}>
                {pr.prUrl && (
                  <Button size="small" variant="outlined" endIcon={<OpenInNewIcon />}
                    href={pr.prUrl} target="_blank" rel="noopener noreferrer">
                    View on GitHub
                  </Button>
                )}
              </Box>

              {pr.generatedFiles && Object.keys(pr.generatedFiles).length > 0 && (
                <Accordion sx={{ mt: 1.5 }} onChange={() => loadReviews(pr.id)}>
                  <AccordionSummary expandIcon={<ExpandMoreIcon />}>
                    <Typography variant="body2" sx={{ fontWeight: 600 }}>
                      {Object.keys(pr.generatedFiles).length} Generated Files
                    </Typography>
                  </AccordionSummary>
                  <AccordionDetails>
                    <Stack spacing={0.5}>
                      {Object.keys(pr.generatedFiles).map(file => (
                        <Typography key={file} variant="caption" sx={{ fontFamily: 'monospace', display: 'block' }}>
                          {file}
                        </Typography>
                      ))}
                    </Stack>
                  </AccordionDetails>
                </Accordion>
              )}

              <Accordion sx={{ mt: 1 }} onChange={() => loadRemediations(pr.id)}>
                <AccordionSummary expandIcon={<ExpandMoreIcon />}>
                  <Typography variant="body2" sx={{ fontWeight: 600 }}>
                    CI Remediation Timeline
                  </Typography>
                </AccordionSummary>
                <AccordionDetails>
                  {!expandedRemediations[pr.id] || expandedRemediations[pr.id].length === 0 ? (
                    <Typography variant="body2" color="text.secondary">No CI remediation attempts.</Typography>
                  ) : (
                    <Stack spacing={1}>
                      {expandedRemediations[pr.id].map(attempt => (
                        <Box key={attempt.id} sx={{ p: 1, borderRadius: 1, backgroundColor: 'background.default' }}>
                          <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 0.5 }}>
                            <Typography variant="body2" sx={{ fontWeight: 600 }}>
                              Attempt {attempt.attemptNumber}
                            </Typography>
                            <Chip label={attempt.status} size="small"
                              color={attempt.status === 'RESOLVED' || attempt.status === 'PUSHED' ? 'success' : attempt.status === 'EXHAUSTED' ? 'error' : 'info'} />
                          </Box>
                          {attempt.failureSummary && attempt.failureSummary.length > 0 && (
                            <Box sx={{ ml: 1 }}>
                              {attempt.failureSummary.map((f, idx) => (
                                <Typography key={idx} variant="caption" sx={{ display: 'block' }}>
                                  &bull; {f}
                                </Typography>
                              ))}
                            </Box>
                          )}
                        </Box>
                      ))}
                    </Stack>
                  )}
                </AccordionDetails>
              </Accordion>

              {expandedReviews[pr.id] && expandedReviews[pr.id].length > 0 && (
                <Accordion sx={{ mt: 1 }}>
                  <AccordionSummary expandIcon={<ExpandMoreIcon />}>
                    <Typography variant="body2" sx={{ fontWeight: 600 }}>
                      Self-Review History ({expandedReviews[pr.id].length} iterations)
                    </Typography>
                  </AccordionSummary>
                  <AccordionDetails>
                    <Stack spacing={1}>
                      {expandedReviews[pr.id].map(review => (
                        <Box key={review.id} sx={{ p: 1, borderRadius: 1, backgroundColor: 'background.default' }}>
                          <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 0.5 }}>
                            <Typography variant="body2" sx={{ fontWeight: 600 }}>
                              Iteration {review.iterationNumber}
                            </Typography>
                            <Chip label={review.verdict} size="small"
                              color={review.verdict === 'PASS' ? 'success' : 'error'} />
                          </Box>
                          {review.issuesFound && review.issuesFound.length > 0 && (
                            <Box sx={{ ml: 1 }}>
                              {review.issuesFound.map((issue, idx) => (
                                <Typography key={idx} variant="caption" sx={{ display: 'block' }}>
                                  &bull; {issue}
                                </Typography>
                              ))}
                            </Box>
                          )}
                        </Box>
                      ))}
                    </Stack>
                  </AccordionDetails>
                </Accordion>
              )}
            </CardContent>
          </Card>
        ))}
      </Stack>

      <Dialog open={dialogOpen} onClose={() => setDialogOpen(false)} maxWidth="sm" fullWidth>
        <DialogTitle>Create Pull Request</DialogTitle>
        <DialogContent>
          <Stack spacing={2} sx={{ mt: 1 }}>
            <TextField
              fullWidth size="small" label="Session ID *"
              placeholder="UUID of a completed analysis session"
              value={sessionId} onChange={e => setSessionId(e.target.value)}
            />
            <TextField
              fullWidth size="small" label="Repository URL *"
              placeholder="https://github.com/owner/repo"
              value={repoUrl} onChange={e => setRepoUrl(e.target.value)}
            />
            <TextField
              fullWidth size="small" label="Base Branch *"
              value={baseBranch} onChange={e => setBaseBranch(e.target.value)}
            />
          </Stack>
          {creating && <LinearProgress sx={{ mt: 2 }} />}
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setDialogOpen(false)} color="inherit">Cancel</Button>
          <Button onClick={handleCreate} variant="contained"
            disabled={creating || !sessionId.trim() || !repoUrl.trim() || !baseBranch.trim()}>
            Create Draft PR
          </Button>
        </DialogActions>
      </Dialog>
    </Box>
  )
}
