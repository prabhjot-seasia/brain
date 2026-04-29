import { useEffect, useMemo, useRef, useState } from 'react'
import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'
import Card from '@mui/material/Card'
import CardContent from '@mui/material/CardContent'
import FormControl from '@mui/material/FormControl'
import InputLabel from '@mui/material/InputLabel'
import Select from '@mui/material/Select'
import MenuItem from '@mui/material/MenuItem'
import Stack from '@mui/material/Stack'
import Chip from '@mui/material/Chip'
import Alert from '@mui/material/Alert'
import CircularProgress from '@mui/material/CircularProgress'
import { brainApi, type ArchitectureView, type Project } from '../../api/brainClient'
import { ResponsiveTable } from '../../components/widgets'

const MAX_MERMAID_LABEL_LENGTH = 60

function sanitizeId(prefix: string, value: string): string {
  return `${prefix}_${value.replace(/[^a-zA-Z0-9_]/g, '_')}`
}

function escapeLabel(value: string): string {
  return value.replace(/"/g, "'").replace(/\n/g, ' ').slice(0, MAX_MERMAID_LABEL_LENGTH)
}

function buildMermaid(view: ArchitectureView): string {
  const lines: string[] = ['flowchart LR']
  const projectNode = sanitizeId('p', view.projectId)
  lines.push(`  ${projectNode}["${escapeLabel(view.projectName || view.projectId)}"]`)
  lines.push(`  classDef project fill:#006ebb,stroke:#003a66,color:#ffffff`)
  lines.push(`  classDef service fill:#e8f1fa,stroke:#006ebb,color:#003a66`)
  lines.push(`  classDef queue fill:#fff4e0,stroke:#cc7a00,color:#5c3500`)
  lines.push(`  classDef endpoint fill:#eaf5ea,stroke:#2e7d32,color:#1b4d1f`)
  lines.push(`  class ${projectNode} project`)

  view.calledServices.forEach(s => {
    const id = sanitizeId('svc', s.id)
    lines.push(`  ${id}(["${escapeLabel(s.name)}"])`)
    lines.push(`  ${projectNode} -- CALLS --> ${id}`)
    lines.push(`  class ${id} service`)
  })

  view.publishesTo.forEach(q => {
    const id = sanitizeId('qpub', q.id)
    const label = `${q.queueType ?? 'QUEUE'}: ${q.name}`
    lines.push(`  ${id}[/"${escapeLabel(label)}"/]`)
    lines.push(`  ${projectNode} -- PUBLISHES --> ${id}`)
    lines.push(`  class ${id} queue`)
  })

  view.consumesFrom.forEach(q => {
    const id = sanitizeId('qsub', q.id)
    const label = `${q.queueType ?? 'QUEUE'}: ${q.name}`
    lines.push(`  ${id}[/"${escapeLabel(label)}"/]`)
    lines.push(`  ${id} -- CONSUMED BY --> ${projectNode}`)
    lines.push(`  class ${id} queue`)
  })

  view.exposedEndpoints.slice(0, 25).forEach(e => {
    const id = sanitizeId('ep', e.id)
    const label = `${e.httpMethod ?? 'GET'} ${e.path}`
    lines.push(`  ${id}(["${escapeLabel(label)}"])`)
    lines.push(`  ${projectNode} -- EXPOSES --> ${id}`)
    lines.push(`  class ${id} endpoint`)
  })

  return lines.join('\n')
}

export default function ArchitecturePage() {
  const [projects, setProjects] = useState<Project[]>([])
  const [selected, setSelected] = useState<string>('')
  const [view, setView] = useState<ArchitectureView | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const diagramRef = useRef<HTMLDivElement | null>(null)

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
    setLoading(true)
    setError(null)
    brainApi.getArchitecture(selected)
      .then(setView)
      .catch(() => setError(`Failed to load architecture for ${selected}.`))
      .finally(() => setLoading(false))
  }, [selected])

  const mermaidSource = useMemo(() => (view ? buildMermaid(view) : ''), [view])

  useEffect(() => {
    if (!mermaidSource || !diagramRef.current) return
    let cancelled = false
    const target = diagramRef.current
    target.innerHTML = ''
    import('mermaid').then(({ default: mermaid }) => {
      if (cancelled) return
      mermaid.initialize({ startOnLoad: false, securityLevel: 'strict', theme: 'neutral' })
      const id = `mermaid-${Date.now()}`
      mermaid.render(id, mermaidSource)
        .then(({ svg }) => { if (!cancelled) target.innerHTML = svg })
        .catch(err => {
          if (!cancelled) {
            target.innerHTML = ''
            setError(`Diagram render failed: ${err?.message ?? 'unknown error'}`)
          }
        })
    }).catch(err => {
      if (!cancelled) setError(`Mermaid load failed: ${err?.message ?? 'unknown error'}`)
    })
    return () => { cancelled = true }
  }, [mermaidSource])

  return (
    <Box>
      <Typography variant="h5" sx={{ mb: 3 }}>Architecture</Typography>

      <FormControl sx={{ mb: 3, minWidth: { xs: '100%', sm: 280 } }} size="small">
        <InputLabel id="arch-project-label">Project</InputLabel>
        <Select
          labelId="arch-project-label"
          label="Project"
          value={selected}
          onChange={e => setSelected(String(e.target.value))}
        >
          {projects.map(p => (
            <MenuItem key={p.id} value={p.id}>{p.name || p.id}</MenuItem>
          ))}
        </Select>
      </FormControl>

      {error && <Alert severity="error" sx={{ mb: 2 }}>{error}</Alert>}

      {loading && (
        <Box sx={{ display: 'flex', justifyContent: 'center', py: 4 }}>
          <CircularProgress size={24} />
        </Box>
      )}

      {!loading && view && (
        <>
          <Card sx={{ mb: 3 }}>
            <CardContent>
              <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap sx={{ mb: 2 }}>
                {view.kind && <Chip label={`kind: ${view.kind}`} size="small" />}
                <Chip label={`calls: ${view.counts.calledServices ?? 0}`} size="small" />
                <Chip label={`publishes: ${view.counts.publishesTo ?? 0}`} size="small" />
                <Chip label={`consumes: ${view.counts.consumesFrom ?? 0}`} size="small" />
                <Chip label={`endpoints: ${view.counts.endpoints ?? 0}`} size="small" />
              </Stack>
              <Box
                ref={diagramRef}
                data-testid="architecture-diagram"
                sx={{
                  width: '100%',
                  overflowX: 'auto',
                  '& svg': { maxWidth: '100%', height: 'auto' },
                }}
              />
            </CardContent>
          </Card>

          {view.calledServices.length > 0 && (
            <Box sx={{ mb: 2 }}>
              <Typography variant="h6" gutterBottom>Called services</Typography>
              <ResponsiveTable
                rows={view.calledServices}
                rowKey={s => s.id}
                columns={[
                  { key: 'name', label: 'Name', primary: true, render: s => s.name },
                  { key: 'baseUrl', label: 'Base URL', wordBreak: true, render: s => s.baseUrlTemplate ?? '—' },
                  { key: 'resolved', label: 'Resolved project', render: s => s.inferredProjectId ?? '—' },
                  { key: 'source', label: 'Source', render: s => s.source ?? '—' },
                ]}
              />
            </Box>
          )}

          {(view.publishesTo.length > 0 || view.consumesFrom.length > 0) && (
            <Box sx={{ mb: 2 }}>
              <Typography variant="h6" gutterBottom>Queues</Typography>
              <ResponsiveTable
                rows={[
                  ...view.publishesTo.map(q => ({ ...q, _direction: 'publish' as const, _key: `pub-${q.id}` })),
                  ...view.consumesFrom.map(q => ({ ...q, _direction: 'consume' as const, _key: `sub-${q.id}` })),
                ]}
                rowKey={q => q._key}
                columns={[
                  { key: 'name', label: 'Name', primary: true, render: q => q.name },
                  {
                    key: 'direction', label: 'Direction',
                    render: q => <Chip size="small" color={q._direction === 'publish' ? 'warning' : 'info'} label={q._direction} />,
                  },
                  { key: 'type', label: 'Type', render: q => q.queueType ?? '—' },
                  { key: 'arn', label: 'ARN', wordBreak: true, render: q => q.arn ?? '—' },
                ]}
              />
            </Box>
          )}

          {view.exposedEndpoints.length > 0 && (
            <Box sx={{ mb: 2 }}>
              <Typography variant="h6" gutterBottom>Exposed endpoints ({view.exposedEndpoints.length})</Typography>
              <ResponsiveTable
                rows={view.exposedEndpoints}
                rowKey={e => e.id}
                columns={[
                  {
                    key: 'path', label: 'Path', primary: true, wordBreak: true,
                    render: e => (
                      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, flexWrap: 'wrap' }}>
                        {e.httpMethod && <Chip size="small" label={e.httpMethod} color="primary" />}
                        <Typography component="span" sx={{ fontFamily: 'monospace', wordBreak: 'break-all' }}>{e.path}</Typography>
                      </Box>
                    ),
                  },
                  { key: 'method', label: 'Method', render: e => e.httpMethod ?? '—' },
                  { key: 'source', label: 'Source', render: e => e.source ?? '—' },
                ]}
              />
            </Box>
          )}

          {view.runtimeEdges && view.runtimeEdges.length > 0 && (
            <Box sx={{ mb: 2 }}>
              <Typography variant="h6" gutterBottom>Runtime calls (X-Ray / OTEL)</Typography>
              <ResponsiveTable
                rows={view.runtimeEdges.map((e, i) => ({ ...e, _key: `${e.fromService}-${e.toService}-${i}` }))}
                rowKey={e => e._key}
                columns={[
                  {
                    key: 'edge', label: 'From → To', primary: true, wordBreak: true,
                    render: e => `${e.fromService} → ${e.toService}`,
                  },
                  { key: 'freq', label: 'Frequency', align: 'right', render: e => e.frequency.toLocaleString() },
                  { key: 'p50', label: 'p50 ms', align: 'right', render: e => e.p50LatencyMs.toFixed(1) },
                  { key: 'p99', label: 'p99 ms', align: 'right', render: e => e.p99LatencyMs.toFixed(1) },
                  { key: 'errors', label: 'Error %', align: 'right', render: e => (e.errorRate * 100).toFixed(2) },
                  { key: 'source', label: 'Source', render: e => e.source ?? '—' },
                ]}
              />
            </Box>
          )}

          {view.calledServices.length === 0 &&
           view.publishesTo.length === 0 &&
           view.consumesFrom.length === 0 &&
           view.exposedEndpoints.length === 0 && (
            <Alert severity="info">
              No service / queue / endpoint edges found yet for this project. Re-ingest after the
              relevant parsers land, or check that the repo has Spring YAML, Kafka/SQS listeners,
              or REST controllers.
            </Alert>
          )}
        </>
      )}
    </Box>
  )
}
