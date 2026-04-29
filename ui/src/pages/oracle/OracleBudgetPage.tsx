import { useEffect, useState } from 'react'
import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'
import { brainApi, type OracleBudget, type Project } from '../../api/brainClient'
import { DataState, ResponsiveTable, StatusChip, type ResponsiveTableColumn } from '../../components/widgets'

interface Row {
  project: Project
  budget?: OracleBudget
  error?: string
}

const COLUMNS: ResponsiveTableColumn<Row>[] = [
  { key: 'project', label: 'Project', primary: true, render: r => r.project.name || r.project.id },
  { key: 'tier', label: 'Tier', render: r => r.budget
      ? <StatusChip status={r.budget.tier} />
      : r.error
        ? <StatusChip status="error" />
        : <StatusChip status={null} /> },
  { key: 'recallK', label: 'recall-K', align: 'right', render: r => r.budget?.recallK ?? '—' },
  { key: 'rerankK', label: 'rerank-K', align: 'right', render: r => r.budget?.rerankK ?? '—' },
  { key: 'recallTok', label: 'recall tok', align: 'right', render: r => r.budget?.recallTokenCost?.toLocaleString() ?? '—' },
  { key: 'rerankTok', label: 'rerank tok', align: 'right', render: r => r.budget?.rerankTokenCost?.toLocaleString() ?? '—' },
  { key: 'groundingTok', label: 'grounding tok', align: 'right', render: r => r.budget?.groundingTokenCost?.toLocaleString() ?? '—' },
  { key: 'notes', label: 'Notes', render: r => r.error ?? '' },
]

export default function OracleBudgetPage() {
  const [rows, setRows] = useState<Row[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    setLoading(true); setError(null)
    brainApi.listProjects()
      .then(async (projects) => {
        const settled = await Promise.all(projects.map(async p => {
          try {
            const budget = await brainApi.getOracleBudget(p.id)
            return { project: p, budget } satisfies Row
          } catch (e: unknown) {
            return { project: p, error: e instanceof Error ? e.message : String(e) } satisfies Row
          }
        }))
        setRows(settled)
      })
      .catch(() => setError('Failed to load projects.'))
      .finally(() => setLoading(false))
  }, [])

  return (
    <Box>
      <Typography variant="h5" sx={{ mb: 1 }}>ORACLE Retrieval Budgets</Typography>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 3 }}>
        Per-project recall/rerank K computed by <code>OraclePromptOptimizer</code> from the strictest
        SLO target. Tiers: HIGH (≥99.9%, 200/20), MEDIUM (≥99.5%, 100/10), LOW (&lt;99.5%, 50/5),
        DEFAULT (no SLO node, 100/10).
      </Typography>

      <DataState
        loading={loading}
        error={error}
        isEmpty={rows.length === 0}
        emptyTitle="No projects ingested yet."
        emptyDescription="Ingest a project to see its budget."
      >
        <ResponsiveTable rows={rows} columns={COLUMNS} rowKey={r => r.project.id} />
      </DataState>
    </Box>
  )
}
