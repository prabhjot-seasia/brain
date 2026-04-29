import { useEffect, useRef, useState } from 'react'
import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'
import Card from '@mui/material/Card'
import CardContent from '@mui/material/CardContent'
import TextField from '@mui/material/TextField'
import Button from '@mui/material/Button'
import MenuItem from '@mui/material/MenuItem'
import Alert from '@mui/material/Alert'
import LinearProgress from '@mui/material/LinearProgress'
import Chip from '@mui/material/Chip'
import Stack from '@mui/material/Stack'
import IconButton from '@mui/material/IconButton'
import Divider from '@mui/material/Divider'
import DeleteIcon from '@mui/icons-material/Delete'
import CheckCircleIcon from '@mui/icons-material/CheckCircle'
import AutoAwesomeIcon from '@mui/icons-material/AutoAwesome'
import OpenInNewIcon from '@mui/icons-material/OpenInNew'
import { brainApi, type ProposedTicketDto } from '../../api/brainClient'
import { useJobStream } from '../../hooks/useJobStream'
import { startWatchingJob } from '../../components/widgets'

const ISSUE_TYPES = ['Story', 'Task', 'Bug']
const PRIORITIES = ['High', 'Medium', 'Low']
const STORY_POINTS = [1, 2, 3, 5, 8, 13]

export default function TicketProposalPage() {
  
  const [content, setContent] = useState('')
  const [projectKey, setProjectKey] = useState('')
  const [loading, setLoading] = useState(false)
  const [creating, setCreating] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [proposalId, setProposalId] = useState<string | null>(null)
  const [tickets, setTickets] = useState<ProposedTicketDto[]>([])
  const [createdKeys, setCreatedKeys] = useState<string[] | null>(null)
  const [jiraSiteUrl, setJiraSiteUrl] = useState('')
  const [proposeJobId, setProposeJobId] = useState<string | null>(null)
  const [createJobId, setCreateJobId] = useState<string | null>(null)
  const proposalIdRef = useRef<string | null>(null)
  proposalIdRef.current = proposalId

  useJobStream(proposeJobId, {
    onTerminal: async snap => {
      const pid = proposalIdRef.current;

      if (snap?.status === 'SUCCEEDED' && pid) {
        try {
          const status = await brainApi.getProposalStatus(pid)
          setTickets(status.tickets ?? [])
        } catch {
          setError('Tickets generated but could not be loaded — refresh the page.')
        }
      } else if (snap?.status === 'FAILED') {
        setError(snap.errorMessage || 'Ticket proposal failed.')
      }
      setProposeJobId(null)
      setLoading(false)
    },
  })

  useJobStream(createJobId, {
    onTerminal: snap => {
      if ((snap?.status === 'SUCCEEDED' || snap?.status === 'PARTIAL') && snap.result) {
        try {
          const payload = JSON.parse(snap.result) as { createdKeys?: string[] }
          setCreatedKeys(payload.createdKeys ?? [])
        } catch {
          setError('Tickets created but result was unreadable.')
        }
      } else if (snap?.status === 'FAILED') {
        setError(snap.errorMessage || 'Ticket creation failed.')
      }
      setCreateJobId(null)
      setCreating(false)
    },
  })

  useEffect(() => {
    brainApi.getJiraStatus().then(s => {
      if (s.siteUrl) setJiraSiteUrl(s.siteUrl.replace(/\/$/, ''))
    }).catch(() => setError('Failed to load Jira configuration.'))
  }, [])

  const handlePropose = async () => {
    if (!content.trim() || !projectKey.trim()) {
      setError('Paste your document content and enter a Jira project key.')
      return
    }
    setError(null)
    setLoading(true)
    setTickets([])
    setCreatedKeys(null)
    try {
      const res = await brainApi.proposeTickets(content, projectKey)
      setProposalId(res.proposalId)
      setProposeJobId(res.jobId)
      startWatchingJob(res.jobId, `Propose tickets for ${projectKey}`)
    } catch {
      setError('Failed to generate ticket proposals. Check that the Brain API is running.')
      setLoading(false)
    }
  }

  const updateTicket = (index: number, field: keyof ProposedTicketDto, value: string | number) => {
    setTickets(prev => prev.map((t, i) => i === index ? { ...t, [field]: value } : t))
  }

  const removeTicket = (index: number) => {
    setTickets(prev => prev.filter((_, i) => i !== index))
  }

  const handleCreate = async () => {
    if (tickets.length === 0) return
    setCreating(true)
    setError(null)
    try {
      const res = await brainApi.createTickets(proposalId ?? '', tickets, projectKey)
      setCreateJobId(res.jobId)
      startWatchingJob(res.jobId, `Create ${tickets.length} ticket(s) in ${projectKey}`)
    } catch {
      setError('Failed to create tickets in Jira. Check your Jira connection.')
      setCreating(false)
    }
  }

  const reset = () => {
    setTickets([])
    setCreatedKeys(null)
    setProposalId(null)
    setError(null)
    setContent('')
  }
  return (
    <Box>
      <Typography variant="h5" sx={{ mb: 3 }}>Create Jira Tickets</Typography>

      {!tickets.length && !createdKeys && (
        <Card sx={{ mb: 3 }}>
          <CardContent sx={{ p: { xs: 2, sm: 3 } }}>
            <Typography variant="subtitle2" sx={{ mb: 2, color: 'primary.main', fontWeight: 600 }}>
              Paste a PRD, grooming notes, or requirements document
            </Typography>

            <TextField
              fullWidth multiline rows={8} size="small"
              label="Document content"
              placeholder="Paste your PRD, meeting notes, or requirements here..."
              value={content} onChange={e => setContent(e.target.value)}
              sx={{ mb: 2 }}
            />

            <Box sx={{ display: 'flex', flexDirection: { xs: 'column', sm: 'row' }, gap: 2, alignItems: { sm: 'center' } }}>
              <TextField size="small" label="Jira Project Key *"
                placeholder="e.g., PAYMENTS"
                value={projectKey} onChange={e => setProjectKey(e.target.value.toUpperCase())}
                sx={{ minWidth: { xs: '100%', sm: 180 } }}
              />
              <Button variant="contained" onClick={handlePropose}
                disabled={loading || !content.trim() || !projectKey.trim()}
                startIcon={<AutoAwesomeIcon />}
                sx={{ ml: { sm: 'auto' } }}>
                Propose Tickets
              </Button>
            </Box>

            {loading && <LinearProgress sx={{ mt: 2 }} />}
            {error && <Alert severity="error" sx={{ mt: 2 }}>{error}</Alert>}
          </CardContent>
        </Card>
      )}

      {tickets.length > 0 && !createdKeys && (
        <>
          <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', mb: 2 }}>
            <Typography variant="h6">{tickets.length} Proposed Tickets</Typography>
            <Box sx={{ display: 'flex', gap: 1 }}>
              <Button color="inherit" onClick={reset}>Back</Button>
              <Button variant="contained" onClick={handleCreate} disabled={creating || tickets.length === 0}
                startIcon={<CheckCircleIcon />}>
                Create All in Jira
              </Button>
            </Box>
          </Box>

          {creating && <LinearProgress sx={{ mb: 2 }} />}
          {error && <Alert severity="error" sx={{ mb: 2 }}>{error}</Alert>}

          <Stack spacing={2}>
            {tickets.map((ticket, i) => (
              <Card key={i}>
                <CardContent sx={{ p: 2 }}>
                  <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', mb: 1.5 }}>
                    <Chip label={`#${i + 1}`} size="small" color="primary" sx={{ mr: 1 }} />
                    <Box sx={{ flex: 1 }}>
                      <TextField fullWidth size="small" label="Title" value={ticket.title}
                        onChange={e => updateTicket(i, 'title', e.target.value)} />
                    </Box>
                    <IconButton size="small" color="error" onClick={() => removeTicket(i)} sx={{ ml: 1 }} aria-label="remove ticket">
                      <DeleteIcon fontSize="small" />
                    </IconButton>
                  </Box>

                  <TextField fullWidth size="small" label="Description" multiline rows={3}
                    value={ticket.description} onChange={e => updateTicket(i, 'description', e.target.value)}
                    sx={{ mb: 1.5 }} />

                  <TextField fullWidth size="small" label="Acceptance Criteria" multiline rows={2}
                    value={ticket.acceptanceCriteria} onChange={e => updateTicket(i, 'acceptanceCriteria', e.target.value)}
                    sx={{ mb: 1.5 }} />

                  <Box sx={{ display: 'flex', flexDirection: { xs: 'column', sm: 'row' }, gap: 1.5 }}>
                    <TextField select size="small" label="Type" value={ticket.issueType}
                      onChange={e => updateTicket(i, 'issueType', e.target.value)}
                      sx={{ minWidth: { xs: '100%', sm: 120 } }}>
                      {ISSUE_TYPES.map(t => <MenuItem key={t} value={t}>{t}</MenuItem>)}
                    </TextField>
                    <TextField select size="small" label="Priority" value={ticket.priority}
                      onChange={e => updateTicket(i, 'priority', e.target.value)}
                      sx={{ minWidth: { xs: '100%', sm: 120 } }}>
                      {PRIORITIES.map(p => <MenuItem key={p} value={p}>{p}</MenuItem>)}
                    </TextField>
                    <TextField select size="small" label="Story Points" value={ticket.storyPoints}
                      onChange={e => updateTicket(i, 'storyPoints', Number(e.target.value))}
                      sx={{ minWidth: { xs: '100%', sm: 130 } }}>
                      {STORY_POINTS.map(p => <MenuItem key={p} value={p}>{p}</MenuItem>)}
                    </TextField>
                  </Box>
                </CardContent>
              </Card>
            ))}
          </Stack>
        </>
      )}

      {createdKeys && (
        <Card sx={{ borderLeft: 4, borderColor: 'success.main' }}>
          <CardContent sx={{ p: { xs: 2, sm: 3 } }}>
            <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 2 }}>
              <CheckCircleIcon color="success" />
              <Typography variant="h6">{createdKeys.filter(k => !k.startsWith('FAILED')).length} Tickets Created</Typography>
              <Button size="small" onClick={reset} sx={{ ml: 'auto' }}>Create More</Button>
            </Box>
            <Divider sx={{ mb: 2 }} />
            <Stack spacing={1}>
              {createdKeys.map((key, i) => (
                <Box key={i} sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
                  {key.startsWith('FAILED') ? (
                    <Chip label="Failed" size="small" color="error" />
                  ) : (
                    <Chip label={key} size="small" color="primary" variant="outlined"
                      icon={<OpenInNewIcon />}
                      onClick={() => jiraSiteUrl && window.open(`${jiraSiteUrl}/browse/${key}`, '_blank')}
                      clickable />
                  )}
                  <Typography variant="body2">
                    {key.startsWith('FAILED') ? key.replace('FAILED:', '') : tickets[i]?.title}
                  </Typography>
                </Box>
              ))}
            </Stack>
          </CardContent>
        </Card>
      )}
    </Box>
  )
}
