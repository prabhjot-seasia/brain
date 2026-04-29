import { useState } from 'react'
import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'
import Card from '@mui/material/Card'
import CardContent from '@mui/material/CardContent'
import TextField from '@mui/material/TextField'
import Button from '@mui/material/Button'
import Alert from '@mui/material/Alert'
import LinearProgress from '@mui/material/LinearProgress'
import Grid from '@mui/material/Grid'
import Chip from '@mui/material/Chip'
import RocketLaunchIcon from '@mui/icons-material/RocketLaunch'
import CheckCircleIcon from '@mui/icons-material/CheckCircle'
import ErrorIcon from '@mui/icons-material/Error'
import { brainApi } from '../../api/brainClient'
import { useJobStream } from '../../hooks/useJobStream'
import { startWatchingJob } from '../../components/widgets'

export default function IngestPage() {
  const [form, setForm] = useState({
    projectId: '', projectName: '', repoUrl: '', branch: '', description: '',
  })
  const [loading, setLoading] = useState(false)
  const [activeJobId, setActiveJobId] = useState<string | null>(null)
  const [attachedToExisting, setAttachedToExisting] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [detected, setDetected] = useState<{ language?: string; framework?: string; buildTool?: string } | null>(null)

  const { snapshot } = useJobStream(activeJobId, {
    onTerminal: async snap => {
      if (snap?.status === 'SUCCEEDED') {
        try {
          const project = await brainApi.getProject(form.projectId)
          setDetected({ language: project.language, framework: project.framework, buildTool: project.buildTool })
        } catch { /* keep success state even if fetch fails */ }
      }
    },
  })

  const status = snapshot?.status ?? null
  const progressPct = snapshot?.progressPct ?? null
  const progressMsg = snapshot?.progressMsg ?? null
  const ingesting = status === 'QUEUED' || status === 'RUNNING'
  const success = status === 'SUCCEEDED'
  const ingestionError = status === 'FAILED' ? (snapshot?.errorMessage || 'Ingestion failed.') : null

  const handleSubmit = async () => {
    if (!form.projectId || !form.projectName || !form.repoUrl) {
      setError('Project ID, Name, and GitHub URL are required.')
      return
    }
    setError(null)
    setDetected(null)
    setLoading(true)
    try {
      const resp = await brainApi.ingest({
        projectId: form.projectId,
        projectName: form.projectName,
        repoUrl: form.repoUrl,
        branch: form.branch || undefined,
        description: form.description || undefined,
      })
      setActiveJobId(resp.jobId)
      setAttachedToExisting(resp.attachedToExisting)
      startWatchingJob(resp.jobId, `Ingest ${form.projectId}`)
      setLoading(false)
    } catch {
      setError('Ingestion failed. Check that the Brain API is running and the repo URL is accessible.')
      setLoading(false)
    }
  }

  const set = (field: string) => (e: React.ChangeEvent<HTMLInputElement>) =>
    setForm(f => ({ ...f, [field]: e.target.value }))

  const reset = () => {
    setActiveJobId(null)
    setAttachedToExisting(false)
    setDetected(null)
    setError(null)
  }

  return (
    <Box>
      <Typography variant="h5" sx={{ mb: 3 }}>Ingest Project</Typography>

      {ingestionError && (
        <Alert severity="error" icon={<ErrorIcon />} sx={{ mb: 2 }}
          action={<Button color="inherit" size="small" onClick={reset}>Try Again</Button>}>
          <strong>Ingestion failed for {form.projectId}</strong>
          <br />{ingestionError}
        </Alert>
      )}

      {success && (
        <Alert severity="success" icon={<CheckCircleIcon />} sx={{ mb: 2 }}
          action={<Button color="inherit" size="small" onClick={reset}>Ingest Another</Button>}>
          Ingestion complete for <strong>{form.projectId}</strong>.
          {detected && (
            <Box sx={{ mt: 1, display: 'flex', gap: 1 }}>
              {detected.language && <Chip label={detected.language} size="small" color="primary" variant="outlined" />}
              {detected.framework && <Chip label={detected.framework} size="small" color="secondary" variant="outlined" />}
              {detected.buildTool && <Chip label={detected.buildTool} size="small" variant="outlined" />}
            </Box>
          )}
        </Alert>
      )}

      {ingesting && (
        <Alert severity="info" sx={{ mb: 2 }}>
          {attachedToExisting
            ? <>Already ingesting <strong>{form.projectId}</strong> — joined the in-flight run.</>
            : <>Ingesting <strong>{form.projectId}</strong> — {progressMsg ?? 'cloning repo and generating embeddings…'}</>}
          <LinearProgress
            sx={{ mt: 1 }}
            variant={progressPct != null ? 'determinate' : 'indeterminate'}
            value={progressPct ?? undefined}
          />
        </Alert>
      )}

      {!success && !ingestionError && !ingesting && (
        <Card>
          <CardContent sx={{ p: 3 }}>
            {error && <Alert severity="error" sx={{ mb: 3 }}>{error}</Alert>}
            {loading && <LinearProgress sx={{ mb: 3 }} />}

            <Grid container spacing={2}>
              <Grid item xs={12} sm={6}>
                <TextField fullWidth label="Project ID *" value={form.projectId} onChange={set('projectId')}
                  helperText="e.g. ce-imei" size="small" />
              </Grid>
              <Grid item xs={12} sm={6}>
                <TextField fullWidth label="Project Name *" value={form.projectName} onChange={set('projectName')}
                  helperText="e.g. ce-IMEI Validation Service" size="small" />
              </Grid>
              <Grid item xs={12} sm={8}>
                <TextField fullWidth label="GitHub URL *" value={form.repoUrl} onChange={set('repoUrl')}
                  helperText="e.g. https://github.com/org/repo.git" size="small" />
              </Grid>
              <Grid item xs={12} sm={4}>
                <TextField fullWidth label="Branch" value={form.branch} onChange={set('branch')}
                  helperText="Defaults to master" size="small" />
              </Grid>
              <Grid item xs={12}>
                <TextField fullWidth label="Description" value={form.description} onChange={set('description')}
                  size="small" multiline rows={2} />
              </Grid>
            </Grid>

            <Box sx={{ mt: 3, display: 'flex', justifyContent: 'flex-end' }}>
              <Button variant="contained" size="large" onClick={handleSubmit}
                disabled={loading} startIcon={<RocketLaunchIcon />}>
                Start Ingestion
              </Button>
            </Box>
          </CardContent>
        </Card>
      )}
    </Box>
  )
}
