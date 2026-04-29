import { useEffect, useState } from 'react'
import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'
import Card from '@mui/material/Card'
import CardContent from '@mui/material/CardContent'
import Chip from '@mui/material/Chip'
import Alert from '@mui/material/Alert'
import LinearProgress from '@mui/material/LinearProgress'
import Stack from '@mui/material/Stack'
import TextField from '@mui/material/TextField'
import MenuItem from '@mui/material/MenuItem'
import ShieldIcon from '@mui/icons-material/Shield'
import CheckCircleIcon from '@mui/icons-material/CheckCircle'
import WarningIcon from '@mui/icons-material/Warning'
import BlockIcon from '@mui/icons-material/Block'
import { brainApi, type AvengerReviewDto, type Project } from '../../api/brainClient'

const AVENGERS = [
  'STARK', 'HAWKEYE', 'VISION', 'WIDOW', 'HULK', 'FURY',
  'FORGE', 'ORACLE', 'MANTIS', 'JARVIS', 'THANOS',
] as const

const VERDICT_COLORS: Record<string, 'success' | 'warning' | 'error'> = {
  APPROVED: 'success',
  CHANGES_REQUESTED: 'warning',
  BLOCKED: 'error',
}

const VERDICT_ICONS: Record<string, typeof CheckCircleIcon> = {
  APPROVED: CheckCircleIcon,
  CHANGES_REQUESTED: WarningIcon,
  BLOCKED: BlockIcon,
}

export default function AvengerReviewPage() {
  const [projects, setProjects] = useState<Project[]>([])
  const [selectedProject, setSelectedProject] = useState('')
  const [selectedAvenger, setSelectedAvenger] = useState<string>('STARK')
  const [reviews, setReviews] = useState<AvengerReviewDto[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    brainApi.listProjects().then(setProjects).catch(() => setError('Failed to load projects.'))
  }, [])

  useEffect(() => {
    if (!selectedProject) {
      setReviews([])
      return
    }
    setLoading(true)
    setError(null)
    brainApi.getAvengerHistory(selectedAvenger, selectedProject, 50)
      .then(setReviews)
      .catch(() => setError('Failed to load Avenger review history.'))
      .finally(() => setLoading(false))
  }, [selectedProject, selectedAvenger])

  const approved = reviews.filter(r => r.verdict === 'APPROVED').length
  const changesRequested = reviews.filter(r => r.verdict === 'CHANGES_REQUESTED').length
  const blocked = reviews.filter(r => r.verdict === 'BLOCKED').length

  return (
    <Box>
      <Typography variant="h5" sx={{ mb: 3 }}>Avenger Reviews</Typography>

      <Box sx={{ display: 'flex', flexDirection: { xs: 'column', sm: 'row' }, gap: 2, mb: 3 }}>
        <TextField
          select size="small" label="Avenger" value={selectedAvenger}
          onChange={e => setSelectedAvenger(e.target.value)}
          sx={{ minWidth: { xs: '100%', sm: 180 } }}
        >
          {AVENGERS.map(a => <MenuItem key={a} value={a}>{a}</MenuItem>)}
        </TextField>

        <TextField
          select size="small" label="Project" value={selectedProject}
          onChange={e => setSelectedProject(e.target.value)}
          sx={{ minWidth: { xs: '100%', sm: 240 } }}
        >
          <MenuItem value="">Select a project</MenuItem>
          {projects.map(p => <MenuItem key={p.id} value={p.id}>{p.id} — {p.name}</MenuItem>)}
        </TextField>

        {selectedProject && (
          <Box sx={{ display: 'flex', gap: 1, ml: { sm: 'auto' }, flexWrap: 'wrap' }}>
            <Chip icon={<ShieldIcon />} label={`${reviews.length} Reviews`} variant="outlined" />
            <Chip icon={<CheckCircleIcon />} label={`${approved} Approved`} color="success" variant="outlined" />
            <Chip icon={<WarningIcon />} label={`${changesRequested} Changes`} color="warning" variant="outlined" />
            <Chip icon={<BlockIcon />} label={`${blocked} Blocked`} color="error" variant="outlined" />
          </Box>
        )}
      </Box>

      {error && <Alert severity="error" sx={{ mb: 2 }} onClose={() => setError(null)}>{error}</Alert>}
      {loading && <LinearProgress sx={{ mb: 2 }} />}

      {!selectedProject && (
        <Alert severity="info">Select a project to view {selectedAvenger}'s review history.</Alert>
      )}

      {selectedProject && !loading && reviews.length === 0 && (
        <Alert severity="info">
          No {selectedAvenger} reviews yet for this project. Trigger one via <code>POST /api/v1/avengers/{selectedAvenger.toLowerCase()}/review</code> or via the MCP tool <code>review_with_{selectedAvenger.toLowerCase()}</code>.
        </Alert>
      )}

      <Stack spacing={1.5}>
        {reviews.map(review => {
          const Icon = VERDICT_ICONS[review.verdict] ?? ShieldIcon
          return (
            <Card key={review.id}>
              <CardContent sx={{ p: { xs: 1.5, sm: 2 }, '&:last-child': { pb: { xs: 1.5, sm: 2 } } }}>
                <Box sx={{ display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: 1, mb: 1 }}>
                  <Chip icon={<ShieldIcon />} label={review.avenger} size="small" color="primary" variant="outlined" />
                  <Chip
                    icon={<Icon sx={{ fontSize: 16 }} />}
                    label={review.verdict.replace('_', ' ')}
                    color={VERDICT_COLORS[review.verdict] ?? 'default'}
                    size="small"
                  />
                  <Typography variant="caption" color="text.secondary" sx={{ ml: { sm: 'auto' } }}>
                    {new Date(review.createdAt).toLocaleString()}
                  </Typography>
                </Box>

                {review.summary && (
                  <Typography variant="body2" sx={{ fontWeight: 500, mb: review.issues?.length ? 1 : 0 }}>
                    {review.summary}
                  </Typography>
                )}

                {review.issues && review.issues.length > 0 && (
                  <Box component="ul" sx={{ pl: 2.5, mt: 0.5, mb: 0.5, '& li': { mb: 0.25 } }}>
                    {review.issues.map((issue, idx) => (
                      <Typography component="li" variant="body2" key={idx} color="text.secondary">
                        {issue}
                      </Typography>
                    ))}
                  </Box>
                )}

                <Box sx={{ display: 'flex', gap: 2, mt: 1, flexWrap: 'wrap' }}>
                  <Typography variant="caption" color="text.secondary">
                    Latency: {review.latencyMs}ms
                  </Typography>
                  {(review.tokensIn > 0 || review.tokensOut > 0) && (
                    <Typography variant="caption" color="text.secondary">
                      Tokens: {review.tokensIn} in / {review.tokensOut} out
                    </Typography>
                  )}
                </Box>
              </CardContent>
            </Card>
          )
        })}
      </Stack>
    </Box>
  )
}
