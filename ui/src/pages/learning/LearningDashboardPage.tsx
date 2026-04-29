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
import TrendingUpIcon from '@mui/icons-material/TrendingUp'
import TrendingDownIcon from '@mui/icons-material/TrendingDown'
import AutoFixHighIcon from '@mui/icons-material/AutoFixHigh'
import { brainApi, type LearningEventDto, type Project } from '../../api/brainClient'

const EVENT_TYPE_LABELS: Record<string, string> = {
  CONVENTION_WEIGHT_ADJUSTED: 'Weight Adjusted',
  CONVENTION_DISCOVERED: 'New Convention',
  CONVENTION_DEPRECATED: 'Deprecated',
  AVENGER_VIOLATION_OBSERVED: 'Avenger Violation',
}

const EVENT_TYPE_COLORS: Record<string, 'info' | 'success' | 'warning' | 'error'> = {
  CONVENTION_WEIGHT_ADJUSTED: 'info',
  CONVENTION_DISCOVERED: 'success',
  CONVENTION_DEPRECATED: 'warning',
  AVENGER_VIOLATION_OBSERVED: 'error',
}

export default function LearningDashboardPage() {
  const [events, setEvents] = useState<LearningEventDto[]>([])
  const [projects, setProjects] = useState<Project[]>([])
  const [selectedProject, setSelectedProject] = useState('')
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    brainApi.listProjects().then(setProjects).catch(() => setError('Failed to load projects.'))
  }, [])

  useEffect(() => {
    setLoading(true)
    brainApi.getLearningEvents(selectedProject || undefined)
      .then(setEvents)
      .catch(() => setError('Failed to load learning events.'))
      .finally(() => setLoading(false))
  }, [selectedProject])

  const weightIncreases = events.filter(e => e.oldWeight !== null && e.newWeight !== null && e.newWeight > e.oldWeight).length
  const weightDecreases = events.filter(e => e.oldWeight !== null && e.newWeight !== null && e.newWeight < e.oldWeight).length

  return (
    <Box>
      <Typography variant="h5" sx={{ mb: 3 }}>Learning Dashboard</Typography>

      <Box sx={{ display: 'flex', flexDirection: { xs: 'column', sm: 'row' }, gap: 2, mb: 3 }}>
        <TextField
          select size="small" label="Filter by Project" value={selectedProject}
          onChange={e => setSelectedProject(e.target.value)}
          sx={{ minWidth: { xs: '100%', sm: 220 } }}
        >
          <MenuItem value="">All Projects</MenuItem>
          {projects.map(p => <MenuItem key={p.id} value={p.id}>{p.id} — {p.name}</MenuItem>)}
        </TextField>

        <Box sx={{ display: 'flex', gap: 2, ml: { sm: 'auto' }, flexWrap: 'wrap' }}>
          <Chip icon={<AutoFixHighIcon />} label={`${events.length} Events`} variant="outlined" />
          <Chip icon={<TrendingUpIcon />} label={`${weightIncreases} Reinforced`} color="success" variant="outlined" />
          <Chip icon={<TrendingDownIcon />} label={`${weightDecreases} Adjusted Down`} color="warning" variant="outlined" />
        </Box>
      </Box>

      {error && <Alert severity="error" sx={{ mb: 2 }} onClose={() => setError(null)}>{error}</Alert>}
      {loading && <LinearProgress sx={{ mb: 2 }} />}

      {!loading && events.length === 0 && (
        <Alert severity="info">
          No learning events yet. Events are created when Brain-generated PRs are merged and analyzed.
        </Alert>
      )}

      <Stack spacing={1.5}>
        {events.map(event => (
          <Card key={event.id}>
            <CardContent sx={{ p: { xs: 1.5, sm: 2 }, '&:last-child': { pb: { xs: 1.5, sm: 2 } } }}>
              <Box sx={{ display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: 1, mb: 0.5 }}>
                <Chip
                  label={EVENT_TYPE_LABELS[event.eventType] ?? event.eventType}
                  color={EVENT_TYPE_COLORS[event.eventType] ?? 'default'}
                  size="small"
                />
                {event.oldWeight !== null && event.newWeight !== null && (
                  <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.5 }}>
                    {event.newWeight > event.oldWeight
                      ? <TrendingUpIcon color="success" sx={{ fontSize: 18 }} />
                      : <TrendingDownIcon color="warning" sx={{ fontSize: 18 }} />
                    }
                    <Typography variant="caption" sx={{ fontFamily: 'monospace' }}>
                      {event.oldWeight.toFixed(2)} → {event.newWeight.toFixed(2)}
                    </Typography>
                  </Box>
                )}
                <Typography variant="caption" color="text.secondary" sx={{ ml: 'auto' }}>
                  {new Date(event.createdAt).toLocaleString()}
                </Typography>
              </Box>

              {event.conventionRule && (
                <Typography variant="body2" sx={{ fontWeight: 500 }}>
                  {event.conventionRule}
                </Typography>
              )}

              {event.details && event.details.reason && (
                <Typography variant="caption" color="text.secondary" sx={{ mt: 0.5, display: 'block' }}>
                  {String(event.details.reason)}
                </Typography>
              )}

              <Typography variant="caption" color="text.secondary">
                Project: {event.projectId}
              </Typography>
            </CardContent>
          </Card>
        ))}
      </Stack>
    </Box>
  )
}
