import { useEffect, useState } from 'react'
import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'
import Stack from '@mui/material/Stack'
import Chip from '@mui/material/Chip'
import FormControl from '@mui/material/FormControl'
import InputLabel from '@mui/material/InputLabel'
import Select from '@mui/material/Select'
import MenuItem from '@mui/material/MenuItem'
import { brainApi, type AsffBatch, type AsffFinding, type Project } from '../../api/brainClient'
import { DataState, ResponsiveTable, StatusChip, type ResponsiveTableColumn } from '../../components/widgets'

const COLUMNS: ResponsiveTableColumn<AsffFinding>[] = [
  { key: 'title', label: 'Title', primary: true, render: f => f.Title, wordBreak: true },
  { key: 'severity', label: 'Severity', render: f => <StatusChip status={f.Severity.Label} /> },
  { key: 'workflow', label: 'Workflow', render: f => f.Workflow.Status },
  { key: 'compliance', label: 'Compliance', render: f => f.Compliance.Status },
  { key: 'created', label: 'Created', render: f => f.CreatedAt ?? '—' },
]

export default function HawkeyeAsffPage() {
  const [projects, setProjects] = useState<Project[]>([])
  const [selected, setSelected] = useState<string>('')
  const [batch, setBatch] = useState<AsffBatch | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    brainApi.listProjects()
      .then(list => {
        setProjects(list)
        if (list.length > 0 && !selected) setSelected(list[0].id)
      })
      .catch(() => setError('Failed to load projects.'))
  }, [selected])

  useEffect(() => {
    if (!selected) return
    setLoading(true); setError(null)
    brainApi.getHawkeyeAsff(selected)
      .then(setBatch)
      .catch(() => setError(`Failed to load ASFF for ${selected}.`))
      .finally(() => setLoading(false))
  }, [selected])

  const findings: AsffFinding[] = batch?.Findings ?? []

  return (
    <Box>
      <Typography variant="h5" sx={{ mb: 1 }}>HAWKEYE Security Findings (ASFF)</Typography>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 3 }}>
        AWS Security Hub Finding Format batch emitted by HAWKEYE Avenger reviews. Compatible with the Security
        Hub schema; configurable via <code>BRAIN_HAWKEYE_*</code> env vars.
      </Typography>

      <FormControl sx={{ mb: 3, minWidth: { xs: '100%', sm: 280 } }} size="small">
        <InputLabel id="hawkeye-project">Project</InputLabel>
        <Select labelId="hawkeye-project" label="Project" value={selected}
                onChange={e => setSelected(String(e.target.value))}>
          {projects.map(p => <MenuItem key={p.id} value={p.id}>{p.name || p.id}</MenuItem>)}
        </Select>
      </FormControl>

      {batch && findings.length > 0 && (
        <Stack direction="row" spacing={1} sx={{ mb: 2 }} flexWrap="wrap" useFlexGap>
          <Chip label={`total: ${findings.length}`} size="small" />
          <Chip color="error"   size="small" label={`critical: ${findings.filter(f => f.Severity.Label === 'CRITICAL').length}`} />
          <Chip color="warning" size="small" label={`high: ${findings.filter(f => f.Severity.Label === 'HIGH').length}`} />
          <Chip color="success" size="small" label={`info: ${findings.filter(f => f.Severity.Label === 'INFORMATIONAL').length}`} />
        </Stack>
      )}

      <DataState
        loading={loading}
        error={error}
        isEmpty={!!batch && findings.length === 0}
        emptyTitle="No HAWKEYE findings yet."
        emptyDescription="Run a HAWKEYE review on this project."
      >
        <ResponsiveTable rows={findings} columns={COLUMNS} rowKey={f => f.Id} />
      </DataState>
    </Box>
  )
}
