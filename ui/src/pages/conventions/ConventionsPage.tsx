import { useEffect, useState } from 'react'
import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'
import Card from '@mui/material/Card'
import CardContent from '@mui/material/CardContent'
import Chip from '@mui/material/Chip'
import TextField from '@mui/material/TextField'
import MenuItem from '@mui/material/MenuItem'
import { brainApi, type Project, type Convention } from '../../api/brainClient'
import {
  DataState,
  ResponsiveTable,
  type ResponsiveTableColumn,
} from '../../components/widgets'

const CATEGORIES = ['', 'string-handling', 'db-migration', 'http-client', 'logging', 'error-handling', 'security']

const COLUMNS: ResponsiveTableColumn<Convention>[] = [
  { key: 'rule', label: 'Rule', primary: true, render: c => c.rule, wordBreak: true },
  {
    key: 'category',
    label: 'Category',
    render: c => <Chip label={c.category} size="small" variant="outlined" color="primary" />,
  },
  {
    key: 'source',
    label: 'Source',
    render: c => (
      <Typography component="span" variant="caption" sx={{ fontFamily: 'monospace', color: 'text.secondary' }}>
        {c.sourceFile}
      </Typography>
    ),
    wordBreak: true,
  },
  {
    key: 'trust',
    label: 'Trust',
    render: c => (
      <Chip
        label={c.trustWeight >= 1.5 ? 'Doc' : 'Inferred'}
        size="small"
        color={c.trustWeight >= 1.5 ? 'primary' : 'default'}
      />
    ),
  },
]

export default function ConventionsPage() {
  const [projects, setProjects] = useState<Project[]>([])
  const [projectId, setProjectId] = useState('')
  const [category, setCategory] = useState('')
  const [conventions, setConventions] = useState<Convention[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    brainApi.listProjects()
      .then(p => { setProjects(p); if (p.length) setProjectId(p[0].id) })
      .catch(() => setError('Failed to load projects.'))
  }, [])

  useEffect(() => {
    if (!projectId) return
    setLoading(true)
    setError(null)
    brainApi.getConventions(projectId, category || undefined)
      .then(setConventions)
      .catch(() => setError('Failed to load conventions.'))
      .finally(() => setLoading(false))
  }, [projectId, category])

  return (
    <Box>
      <Typography variant="h5" sx={{ mb: 3 }}>Conventions</Typography>

      <Card sx={{ mb: 3 }}>
        <CardContent sx={{ p: { xs: 2, sm: 3 } }}>
          <Box sx={{ display: 'flex', flexDirection: { xs: 'column', sm: 'row' }, gap: 2 }}>
            <TextField
              select size="small" label="Project" value={projectId}
              onChange={e => setProjectId(e.target.value)}
              sx={{ minWidth: { xs: '100%', sm: 220 } }}
            >
              {projects.map(p => <MenuItem key={p.id} value={p.id}>{p.id}</MenuItem>)}
            </TextField>
            <TextField
              select size="small" label="Category" value={category}
              onChange={e => setCategory(e.target.value)}
              sx={{ minWidth: { xs: '100%', sm: 180 } }}
            >
              {CATEGORIES.map(c => <MenuItem key={c} value={c}>{c || 'All categories'}</MenuItem>)}
            </TextField>
          </Box>
        </CardContent>
      </Card>

      <DataState
        loading={loading}
        error={error}
        isEmpty={conventions.length === 0}
        emptyTitle="No conventions found."
        emptyDescription="Ingest a project with documentation to populate conventions."
      >
        <ResponsiveTable rows={conventions} columns={COLUMNS} rowKey={c => c.id} />
      </DataState>
    </Box>
  )
}
