import Box from '@mui/material/Box'
import LinearProgress from '@mui/material/LinearProgress'
import Typography from '@mui/material/Typography'
import StatusChip from './StatusChip'
import { useJobStream, type JobSnapshot } from '../../hooks/useJobStream'

export interface JobProgressProps {
  jobId: string | null
  onTerminal?: (snapshot: JobSnapshot | null) => void
  showStatusChip?: boolean
  compact?: boolean
}

export default function JobProgress({ jobId, onTerminal, showStatusChip = true, compact = false }: JobProgressProps) {
  const { snapshot, error } = useJobStream(jobId, { onTerminal })

  if (!jobId) return null

  const status = snapshot?.status ?? 'QUEUED'
  const pct = snapshot?.progressPct ?? 0
  const msg = snapshot?.progressMsg ?? (snapshot ? '' : 'Connecting…')
  const indeterminate = !snapshot || (status !== 'SUCCEEDED' && status !== 'PARTIAL' && status !== 'FAILED' && pct === 0)

  return (
    <Box sx={{ width: '100%', display: 'flex', flexDirection: 'column', gap: compact ? 0.5 : 1 }}>
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, flexWrap: 'wrap' }}>
        {showStatusChip && <StatusChip status={status} />}
        <Typography variant="caption" color="text.secondary" sx={{ flex: 1, minWidth: 0 }}>
          {error ?? msg}
        </Typography>
        {!indeterminate && (
          <Typography variant="caption" color="text.secondary">{pct}%</Typography>
        )}
      </Box>
      <LinearProgress
        variant={indeterminate ? 'indeterminate' : 'determinate'}
        value={indeterminate ? undefined : pct}
        color={status === 'FAILED' ? 'error' : status === 'PARTIAL' ? 'warning' : 'primary'}
      />
    </Box>
  )
}
