import { useEffect, useState } from 'react'
import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'
import Button from '@mui/material/Button'
import AddIcon from '@mui/icons-material/Add'
import { useNavigate } from 'react-router-dom'
import { brainApi, type Project } from '../../api/brainClient'
import {
  DataState,
  ResponsiveTable,
  StatusChip,
  type ResponsiveTableColumn,
} from '../../components/widgets'

const COLUMNS: ResponsiveTableColumn<Project>[] = [
  {
    key: 'id',
    label: 'Project ID',
    primary: true,
    render: p => (
      <Typography component="span" sx={{ fontFamily: 'monospace', color: 'primary.main', fontWeight: 700 }}>
        {p.id}
      </Typography>
    ),
  },
  { key: 'name', label: 'Name', render: p => p.name ?? '—' },
  { key: 'language', label: 'Language', render: p => <StatusChip status={p.language ?? null} variant="outlined" /> },
  { key: 'framework', label: 'Framework', render: p => p.framework ?? '—' },
  {
    key: 'lastIngested',
    label: 'Last Indexed',
    render: p => p.lastIngested ? new Date(p.lastIngested).toLocaleString() : 'Never',
  },
]

export default function ProjectsPage() {
  const [projects, setProjects] = useState<Project[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const navigate = useNavigate()

  useEffect(() => {
    brainApi.listProjects()
      .then(setProjects)
      .catch(() => setError('Failed to load projects. Is the Brain API running?'))
      .finally(() => setLoading(false))
  }, [])

  return (
    <Box>
      <Box sx={{
        display: 'flex',
        flexDirection: { xs: 'column', sm: 'row' },
        justifyContent: 'space-between',
        alignItems: { xs: 'stretch', sm: 'center' },
        gap: 1,
        mb: 3,
      }}>
        <Typography variant="h5">Projects</Typography>
        <Button
          variant="contained"
          startIcon={<AddIcon />}
          onClick={() => navigate('/ingest')}
          sx={{ alignSelf: { xs: 'flex-start', sm: 'auto' } }}
        >
          Ingest Project
        </Button>
      </Box>

      <DataState
        loading={loading}
        error={error}
        isEmpty={projects.length === 0}
        emptyTitle="No projects indexed yet."
        emptyDescription={'Click "Ingest Project" to get started.'}
      >
        <ResponsiveTable
          rows={projects}
          columns={COLUMNS}
          rowKey={p => p.id}
          onRowClick={p => navigate(`/analyze?projectId=${p.id}`)}
        />
      </DataState>
    </Box>
  )
}
