import { useEffect, useRef, useState } from 'react'
import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'
import Card from '@mui/material/Card'
import CardContent from '@mui/material/CardContent'
import FormControl from '@mui/material/FormControl'
import InputLabel from '@mui/material/InputLabel'
import Select from '@mui/material/Select'
import MenuItem from '@mui/material/MenuItem'
import Button from '@mui/material/Button'
import Stack from '@mui/material/Stack'
import Chip from '@mui/material/Chip'
import Alert from '@mui/material/Alert'
import CircularProgress from '@mui/material/CircularProgress'
import TextField from '@mui/material/TextField'
import {
  brainApi,
  type Project,
  type FullDocStatusResponse,
  type FullDocHistoryRow,
} from '../../api/brainClient'
import {
  DataState,
  ResponsiveTable,
  StatusChip as WidgetStatusChip,
  startWatchingJob,
} from '../../components/widgets'
import DownloadIcon from '@mui/icons-material/Download'

const SECTIONS = ['ARCHITECTURE', 'SEQUENCE_DIAGRAM', 'CLASS_DIAGRAM', 'FLOW_DIAGRAM', 'EXPLANATION']
const POLL_MS = 3000

function statusColor(s?: string): 'default' | 'info' | 'success' | 'warning' | 'error' {
  if (!s) return 'default'
  if (s === 'OK' || s === 'COMPLETED') return 'success'
  if (s === 'PARTIAL') return 'warning'
  if (s === 'FAILED') return 'error'
  if (s === 'GENERATING' || s === 'PENDING') return 'info'
  return 'default'
}

function provenanceBadge(status: FullDocStatusResponse | null): { label: string; color: 'success' | 'info' | 'warning' | 'default' } {
  if (!status || !status.generatedAt) return { label: '—', color: 'default' }
  if (status.fromCache) {
    const t = new Date(status.generatedAt).toLocaleTimeString()
    return { label: `From cache · generated ${t}`, color: 'info' }
  }
  const ageSec = (Date.now() - new Date(status.generatedAt).getTime()) / 1000
  if (ageSec < 60) return { label: 'Fresh', color: 'success' }
  if (status.cacheHorizon && new Date(status.cacheHorizon).getTime() < Date.now()) {
    return { label: 'Stale', color: 'warning' }
  }
  const t = new Date(status.generatedAt).toLocaleTimeString()
  return { label: `From cache · generated ${t}`, color: 'info' }
}

export default function FullDocsPage() {
  const [projects, setProjects] = useState<Project[]>([])
  const [projectId, setProjectId] = useState<string>('')
  const [status, setStatus] = useState<FullDocStatusResponse | null>(null)
  const [history, setHistory] = useState<FullDocHistoryRow[]>([])
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [confluenceSpaceKey, setConfluenceSpaceKey] = useState<string>('')
  const [confluenceParentPageId, setConfluenceParentPageId] = useState<string>('')
  const [confluenceMessage, setConfluenceMessage] = useState<string | null>(null)
  const pollRef = useRef<ReturnType<typeof setInterval> | null>(null)

  useEffect(() => {
    brainApi.listProjects()
      .then(list => {
        setProjects(list)
        if (list.length > 0 && !projectId) setProjectId(list[0].id)
      })
      .catch(() => setError('Failed to load projects.'))
  }, [projectId])

  useEffect(() => {
    if (!projectId) return
    brainApi.listFullDocs(projectId).then(setHistory).catch(() => setHistory([]))
  }, [projectId, status?.status])

  const stopPolling = () => {
    if (pollRef.current) {
      clearInterval(pollRef.current)
      pollRef.current = null
    }
  }

  useEffect(() => stopPolling, [])

  const startPolling = (documentId: string, onTerminal?: () => void) => {
    stopPolling()
    pollRef.current = setInterval(async () => {
      try {
        const next = await brainApi.getFullDocStatus(documentId)
        setStatus(next)
        if (next.status !== 'GENERATING' && next.status !== 'PENDING') {
          stopPolling()
          setBusy(false)
          if (onTerminal) onTerminal()
        }
      } catch (e: unknown) {
        stopPolling()
        setBusy(false)
        setError(`Status poll failed: ${e instanceof Error ? e.message : String(e)}`)
      }
    }, POLL_MS)
  }

  const generate = async () => {
    if (!projectId) return
    setError(null); setConfluenceMessage(null); setBusy(true); setStatus(null)
    try {
      const start = await brainApi.generateFullDocs(projectId)
      const initial = await brainApi.getFullDocStatus(start.documentId)
      setStatus({ ...initial, fromCache: !!start.fromCache })
      if (start.jobId && start.attachedToExisting) {
        setError('Already generating for this project — joined that run.')
      }
      if (start.jobId) {
        startWatchingJob(start.jobId, `Documentation for ${projectId}`)
      }
      if (initial.status === 'GENERATING' || initial.status === 'PENDING') {
        startPolling(start.documentId, () => maybePublishConfluence(start.documentId))
      } else {
        setBusy(false)
        await maybePublishConfluence(start.documentId)
      }
    } catch (e: unknown) {
      setBusy(false)
      setError(`Generation failed: ${e instanceof Error ? e.message : String(e)}`)
    }
  }

  const maybePublishConfluence = async (documentId: string) => {
    if (!confluenceSpaceKey || !confluenceParentPageId) return
    try {
      await brainApi.publishDocToConfluence(documentId, {
        spaceKey: confluenceSpaceKey,
        parentPageId: confluenceParentPageId,
      })
      setConfluenceMessage(`Published to Confluence (${confluenceSpaceKey} / ${confluenceParentPageId})`)
    } catch (e: unknown) {
      setConfluenceMessage(`Confluence publish failed: ${e instanceof Error ? e.message : String(e)}`)
    }
  }

  const retryFailed = async () => {
    if (!status) return
    setError(null); setBusy(true)
    try {
      await brainApi.retryFailedSections(status.id)
      startPolling(status.id)
    } catch (e: unknown) {
      setBusy(false)
      setError(`Retry failed: ${e instanceof Error ? e.message : String(e)}`)
    }
  }

  const download = async (documentId: string) => {
    try {
      const blob = await brainApi.downloadFullDocPdf(documentId)
      saveBlob(blob, `project-doc-${documentId}.pdf`)
    } catch (e: unknown) {
      setError(`Download failed: ${e instanceof Error ? e.message : String(e)}`)
    }
  }

  const downloadType = async (documentId: string, docType: string) => {
    try {
      const blob = await brainApi.downloadTypedPdf(documentId, docType)
      saveBlob(blob, `${documentId}-${docType.toLowerCase()}.pdf`)
    } catch (e: unknown) {
      setError(`Download failed: ${e instanceof Error ? e.message : String(e)}`)
    }
  }

  function saveBlob(blob: Blob, filename: string): void {
    const url = window.URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = filename
    document.body.appendChild(a)
    a.click()
    a.remove()
    window.URL.revokeObjectURL(url)
  }

  const badge = provenanceBadge(status)
  const failedCount = status ? Object.values(status.sectionResults || {}).filter(v => v === 'FAILED').length : 0

  return (
    <Box>
      <Typography variant="h5" sx={{ mb: 1 }}>Project Documentation</Typography>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 3 }}>
        Pick a project. One click generates a comprehensive PDF that bundles every documentation
        type Brain knows about — architecture, sequence flows, class model, process flows, and a
        plain-English explanation — plus services, conventions, decisions, SLOs, incidents, flaky
        tests, and recent activity.
      </Typography>

      {error && <Alert severity="error" sx={{ mb: 2 }}>{error}</Alert>}
      {confluenceMessage && (
        <Alert severity={confluenceMessage.startsWith('Published') ? 'success' : 'warning'} sx={{ mb: 2 }}>
          {confluenceMessage}
        </Alert>
      )}

      <Card sx={{ mb: 3 }}>
        <CardContent>
          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2} alignItems={{ sm: 'center' }} sx={{ mb: 2 }}>
            <FormControl size="small" sx={{ minWidth: { xs: '100%', sm: 280 } }}>
              <InputLabel id="full-docs-project-label">Project</InputLabel>
              <Select labelId="full-docs-project-label" label="Project"
                      value={projectId} onChange={e => setProjectId(String(e.target.value))}>
                {projects.map(p => <MenuItem key={p.id} value={p.id}>{p.name || p.id}</MenuItem>)}
              </Select>
            </FormControl>

            <Button variant="contained" disabled={!projectId || busy} onClick={generate}
                    startIcon={busy ? <CircularProgress size={16} /> : null}>
              Generate Full Documentation PDF
            </Button>
          </Stack>

          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
            <TextField size="small" label="Confluence space key (optional)"
                       value={confluenceSpaceKey}
                       onChange={e => setConfluenceSpaceKey(e.target.value)}
                       helperText="e.g. ENG"
                       sx={{ minWidth: { xs: '100%', sm: 200 } }} />
            <TextField size="small" label="Confluence parent page ID (optional)"
                       value={confluenceParentPageId}
                       onChange={e => setConfluenceParentPageId(e.target.value)}
                       helperText="e.g. 123456789"
                       sx={{ minWidth: { xs: '100%', sm: 240 } }} />
          </Stack>
          <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 1 }}>
            Provide both fields to also publish the bundle to Confluence after generation.
            Leave blank to skip. Subsequent merges to this project will auto-update the same page.
          </Typography>
        </CardContent>
      </Card>

      <Card sx={{ mb: 3 }}>
        <CardContent>
          {status && status.generatedAt && (
            <Chip size="small" color={badge.color} label={badge.label} sx={{ mb: 2 }} />
          )}

          {status && (
            <Box sx={{ mt: 3 }}>
              <Typography variant="subtitle2" sx={{ mb: 1 }}>Sections</Typography>
              <Stack spacing={1}>
                {SECTIONS.map(s => {
                  const v = status.sectionResults?.[s] ?? 'PENDING'
                  const available = (status.availablePdfTypes ?? []).includes(s as never)
                  return (
                    <Box key={s} sx={{ display: 'flex', alignItems: 'center', gap: 1, flexWrap: 'wrap' }}>
                      <Chip size="small" color={statusColor(v)} label={`${s.replace('_', ' ')}: ${v}`} />
                      {available && (
                        <Button
                          size="small"
                          startIcon={<DownloadIcon fontSize="small" />}
                          onClick={() => downloadType(status.id, s)}
                        >
                          PDF
                        </Button>
                      )}
                    </Box>
                  )
                })}
              </Stack>

              {status.status === 'COMPLETED' && (
                <Box sx={{ mt: 2 }}>
                  <Button variant="outlined" onClick={() => download(status.id)}>Download PDF</Button>
                </Box>
              )}
              {status.status === 'PARTIAL' && (
                <Box sx={{ mt: 2 }}>
                  <Alert severity="warning" sx={{ mb: 1 }}>
                    {failedCount} of {SECTIONS.length} sections failed. PDF available with the rest.
                  </Alert>
                  <Stack direction="row" spacing={1}>
                    <Button variant="outlined" onClick={() => download(status.id)}>Download PDF</Button>
                    <Button variant="outlined" color="warning" disabled={busy} onClick={retryFailed}>
                      Retry failed sections
                    </Button>
                  </Stack>
                </Box>
              )}
              {status.status === 'FAILED' && (
                <Alert severity="error" sx={{ mt: 2 }}>{status.error || 'Generation failed.'}</Alert>
              )}
            </Box>
          )}
        </CardContent>
      </Card>

      <Typography variant="h6" sx={{ mb: 1 }}>Recent Documentation</Typography>

      <DataState
        isEmpty={history.length === 0}
        emptyTitle="No documentation yet."
      >
        <ResponsiveTable
          rows={history}
          rowKey={row => row.id}
          columns={[
            {
              key: 'generated',
              label: 'Generated',
              primary: true,
              render: row => row.generatedAt ?? row.createdAt ?? '—',
              wordBreak: true,
            },
            {
              key: 'status',
              label: 'Status',
              render: row => <WidgetStatusChip status={row.status} />,
            },
            {
              key: 'size',
              label: 'Size',
              align: 'right',
              render: row => row.sizeBytes ? `${Math.round(row.sizeBytes / 1024)} KB` : '—',
            },
            {
              key: 'action',
              label: 'Action',
              align: 'right',
              render: row => row.sizeBytes > 0
                ? <Button size="small" onClick={() => download(row.id)}>Download</Button>
                : null,
            },
          ]}
        />
      </DataState>
    </Box>
  )
}
