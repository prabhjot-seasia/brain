import { useEffect, useState } from 'react'
import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'
import Card from '@mui/material/Card'
import CardContent from '@mui/material/CardContent'
import FormControl from '@mui/material/FormControl'
import InputLabel from '@mui/material/InputLabel'
import Select from '@mui/material/Select'
import MenuItem from '@mui/material/MenuItem'
import TextField from '@mui/material/TextField'
import Button from '@mui/material/Button'
import Stack from '@mui/material/Stack'
import Alert from '@mui/material/Alert'
import { brainApi, type Project, type RulePackInstallRequest } from '../../api/brainClient'
import { useJobStream } from '../../hooks/useJobStream'
import { startWatchingJob } from '../../components/widgets'

const SAMPLE_PACK = JSON.stringify({
  id: 'spring-boot',
  version: '1.0.0',
  description: 'Spring Boot conventions',
  conventions: [
    { rule: 'Use constructor injection (@RequiredArgsConstructor), not @Autowired fields', category: 'DI', trustWeight: 1.5 },
    { rule: 'Use @Log4j2 instead of @Slf4j', category: 'LOGGING', trustWeight: 1.5 },
  ],
}, null, 2)

export default function RulePacksPage() {
  const [projects, setProjects] = useState<Project[]>([])
  const [selected, setSelected] = useState<string>('')
  const [packJson, setPackJson] = useState<string>(SAMPLE_PACK)
  const [approvalToken, setApprovalToken] = useState('')
  const [busy, setBusy] = useState(false)
  const [info, setInfo] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [installJobId, setInstallJobId] = useState<string | null>(null)

  useJobStream(installJobId, {
    onTerminal: snap => {
      if (snap?.status === 'SUCCEEDED' && snap.result) {
        try {
          const r = JSON.parse(snap.result) as { installed?: number; sourceTag?: string }
          setInfo(`Installed ${r.installed ?? 0} conventions as ${r.sourceTag}`)
        } catch { setInfo('Install completed') }
      } else if (snap?.status === 'FAILED') {
        setError(snap.errorMessage || 'Rule-pack install failed')
      }
      setInstallJobId(null)
      setBusy(false)
    },
  })

  useEffect(() => {
    brainApi.listProjects()
      .then(list => {
        setProjects(list)
        if (list.length > 0 && !selected) setSelected(list[0].id)
      })
      .catch(() => setError('Failed to load projects.'))
  }, [selected])

  const install = async () => {
    setError(null); setInfo(null); setBusy(true)
    try {
      const pack = JSON.parse(packJson)
      const body: RulePackInstallRequest = { version: pack.version, pack }
      const job = await brainApi.installRulePackAsync(selected, body, approvalToken)
      setInstallJobId(job.jobId)
      startWatchingJob(job.jobId, `Install ${pack.id}@${pack.version} on ${selected}`)
    } catch (e: unknown) {
      setError(`Install failed: ${e instanceof Error ? e.message : String(e)}`)
      setBusy(false)
    }
  }

  const uninstall = async () => {
    setError(null); setInfo(null); setBusy(true)
    try {
      const pack = JSON.parse(packJson)
      const result = await brainApi.uninstallRulePack(selected, pack.id, pack.version, approvalToken)
      setInfo(`Removed ${result.removed ?? 0} conventions (${result.sourceTag})`)
    } catch (e: unknown) {
      setError(`Uninstall failed: ${e instanceof Error ? e.message : String(e)}`)
    } finally { setBusy(false) }
  }

  return (
    <Box>
      <Typography variant="h5" sx={{ mb: 1 }}>Convention Rule Packs</Typography>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 3 }}>
        Install versioned packs of conventions per project. THANOS approval required.
      </Typography>

      {error && <Alert severity="error" sx={{ mb: 2 }}>{error}</Alert>}
      {info  && <Alert severity="success" sx={{ mb: 2 }}>{info}</Alert>}

      <Card>
        <CardContent>
          <Stack spacing={2}>
            <FormControl size="small" sx={{ maxWidth: { xs: '100%', sm: 360 } }}>
              <InputLabel id="rulepack-project-label">Project</InputLabel>
              <Select labelId="rulepack-project-label" label="Project" value={selected}
                      onChange={e => setSelected(String(e.target.value))}>
                {projects.map(p => <MenuItem key={p.id} value={p.id}>{p.name || p.id}</MenuItem>)}
              </Select>
            </FormControl>

            <TextField label="Rule pack JSON" multiline minRows={10} fullWidth
                       value={packJson} onChange={e => setPackJson(e.target.value)}
                       sx={{ '& textarea': { fontFamily: 'monospace', fontSize: 13 } }} />

            <TextField label="THANOS approval token (X-Brain-Approval header)" type="password"
                       fullWidth size="small" value={approvalToken}
                       onChange={e => setApprovalToken(e.target.value)}
                       helperText="Required server-side. Configure via BRAIN_RULEPACK_APPROVAL_TOKEN." />

            <Stack direction="row" spacing={1}>
              <Button variant="contained" disabled={busy || !selected} onClick={install}>Install</Button>
              <Button variant="outlined" color="error" disabled={busy || !selected} onClick={uninstall}>Uninstall</Button>
            </Stack>
          </Stack>
        </CardContent>
      </Card>
    </Box>
  )
}
