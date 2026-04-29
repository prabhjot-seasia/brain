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
import Divider from '@mui/material/Divider'
import SavingsIcon from '@mui/icons-material/Savings'
import SpeedIcon from '@mui/icons-material/Speed'
import CachedIcon from '@mui/icons-material/Cached'
import TokenIcon from '@mui/icons-material/DataUsage'
import { brainApi, type TokenUsageSummaryDto, type TokenUsageRecordDto } from '../../api/brainClient'

export default function TokenUsagePage() {
  const [summary, setSummary] = useState<TokenUsageSummaryDto | null>(null)
  const [recent, setRecent] = useState<TokenUsageRecordDto[]>([])
  const [hours, setHours] = useState(24)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    setLoading(true)
    Promise.all([
      brainApi.getTokenUsageSummary(hours),
      brainApi.getTokenUsageRecent(20),
    ])
      .then(([s, r]) => { setSummary(s); setRecent(r) })
      .catch(() => setError('Failed to load token usage data.'))
      .finally(() => setLoading(false))
  }, [hours])

  return (
    <Box>
      <Box sx={{ display: 'flex', flexWrap: 'wrap', alignItems: 'center', justifyContent: 'space-between', mb: 3, gap: 2 }}>
        <Typography variant="h5">Token Usage Dashboard</Typography>
        <TextField select size="small" label="Time Range" id="time-range-select" value={hours}
          onChange={e => setHours(Number(e.target.value))} sx={{ minWidth: 140 }}>
          <MenuItem value={1}>Last Hour</MenuItem>
          <MenuItem value={24}>Last 24h</MenuItem>
          <MenuItem value={168}>Last 7 Days</MenuItem>
          <MenuItem value={720}>Last 30 Days</MenuItem>
        </TextField>
      </Box>

      {error && <Alert severity="error" sx={{ mb: 2 }} onClose={() => setError(null)}>{error}</Alert>}
      {loading && <LinearProgress sx={{ mb: 2 }} />}

      {summary && (
        <>
          <Box sx={{ display: 'flex', flexWrap: 'wrap', gap: 2, mb: 3 }}>
            <Card sx={{ flex: '1 1 150px', minWidth: 150 }}>
              <CardContent sx={{ textAlign: 'center', p: 2, '&:last-child': { pb: 2 } }}>
                <TokenIcon color="primary" />
                <Typography variant="h6">{(summary.totalInputTokens + summary.totalOutputTokens).toLocaleString()}</Typography>
                <Typography variant="caption" color="text.secondary">Total Tokens</Typography>
              </CardContent>
            </Card>
            <Card sx={{ flex: '1 1 150px', minWidth: 150 }}>
              <CardContent sx={{ textAlign: 'center', p: 2, '&:last-child': { pb: 2 } }}>
                <SavingsIcon color="success" />
                <Typography variant="h6">${summary.totalCost.toFixed(2)}</Typography>
                <Typography variant="caption" color="text.secondary">Estimated Cost</Typography>
              </CardContent>
            </Card>
            <Card sx={{ flex: '1 1 150px', minWidth: 150 }}>
              <CardContent sx={{ textAlign: 'center', p: 2, '&:last-child': { pb: 2 } }}>
                <CachedIcon color="info" />
                <Typography variant="h6">{(summary.cacheHitRate * 100).toFixed(0)}%</Typography>
                <Typography variant="caption" color="text.secondary">Cache Hit Rate</Typography>
              </CardContent>
            </Card>
            <Card sx={{ flex: '1 1 150px', minWidth: 150 }}>
              <CardContent sx={{ textAlign: 'center', p: 2, '&:last-child': { pb: 2 } }}>
                <SpeedIcon color="warning" />
                <Typography variant="h6">{summary.totalCalls}</Typography>
                <Typography variant="caption" color="text.secondary">LLM Calls</Typography>
              </CardContent>
            </Card>
          </Box>

          {summary.breakdown.length > 0 && (
            <Card sx={{ mb: 3 }}>
              <CardContent sx={{ p: { xs: 2, sm: 3 } }}>
                <Typography variant="subtitle2" sx={{ mb: 2, fontWeight: 600 }}>Breakdown by Service</Typography>
                <Stack spacing={1}>
                  {summary.breakdown.map((b, i) => (
                    <Box key={i} sx={{ display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: 1,
                      p: 1, borderRadius: 1, backgroundColor: 'background.default' }}>
                      <Chip label={b.serviceName} size="small" variant="outlined" />
                      <Chip label={b.operation} size="small" color="info" />
                      <Typography variant="caption" sx={{ fontFamily: 'monospace' }}>
                        {b.callCount} calls &bull; {b.inputTokens.toLocaleString()} in &bull; {b.outputTokens.toLocaleString()} out &bull; ${b.cost.toFixed(4)}
                      </Typography>
                    </Box>
                  ))}
                </Stack>
              </CardContent>
            </Card>
          )}
        </>
      )}

      {recent.length > 0 && (
        <Card>
          <CardContent sx={{ p: { xs: 2, sm: 3 } }}>
            <Typography variant="subtitle2" sx={{ mb: 2, fontWeight: 600 }}>Recent LLM Calls</Typography>
            <Stack spacing={1}>
              {recent.map(r => (
                <Box key={r.id} sx={{ display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: 1,
                  p: 1, borderRadius: 1, backgroundColor: 'background.default' }}>
                  <Chip label={r.operation} size="small"
                    color={r.cached ? 'success' : 'default'} />
                  {r.cached && <Chip label="CACHED" size="small" color="success" variant="outlined" />}
                  <Typography variant="caption" sx={{ fontFamily: 'monospace' }}>
                    {r.inputTokens} in &bull; {r.outputTokens} out &bull; {r.latencyMs}ms &bull; ${r.costEstimate.toFixed(4)}
                  </Typography>
                  <Typography variant="caption" color="text.secondary" sx={{ ml: 'auto' }}>
                    {new Date(r.createdAt).toLocaleTimeString()}
                  </Typography>
                </Box>
              ))}
            </Stack>
          </CardContent>
        </Card>
      )}

      {!loading && !summary && !error && (
        <Alert severity="info">No token usage data yet. Usage is tracked automatically when Brain makes LLM calls.</Alert>
      )}
    </Box>
  )
}
