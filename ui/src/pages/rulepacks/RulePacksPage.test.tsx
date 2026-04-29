import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { MemoryRouter } from 'react-router-dom'
import RulePacksPage from './RulePacksPage'

const mockListProjects = vi.fn()
const mockInstallAsync = vi.fn()
const mockUninstall = vi.fn()
const mockUseJobStream = vi.fn()
const mockStartWatching = vi.fn()

vi.mock('../../api/brainClient', () => ({
  brainApi: {
    listProjects: (...args: unknown[]) => mockListProjects(...args),
    installRulePackAsync: (...args: unknown[]) => mockInstallAsync(...args),
    uninstallRulePack: (...args: unknown[]) => mockUninstall(...args),
  },
}))

vi.mock('../../hooks/useJobStream', () => ({
  useJobStream: (jobId: string | null, opts: { onTerminal?: (s: unknown) => void } = {}) =>
    mockUseJobStream(jobId, opts),
}))

vi.mock('../../components/widgets', () => ({
  startWatchingJob: (...args: unknown[]) => mockStartWatching(...args),
}))

function renderPage() {
  return render(<MemoryRouter><RulePacksPage /></MemoryRouter>)
}

describe('RulePacksPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockUseJobStream.mockReturnValue({ snapshot: null, status: 'idle' })
  })

  it('install kicks off async job and reports success on terminal event', async () => {
    mockListProjects.mockResolvedValue([
      { id: 'ce-app', name: 'ce-APP', language: 'java', framework: 'spring-boot', buildTool: 'gradle', lastIngested: null, description: '' },
    ])
    mockInstallAsync.mockResolvedValue({
      jobId: 'job-rp-1', jobType: 'RULE_PACK_INSTALL', status: 'QUEUED',
      attachedToExisting: false, projectId: 'ce-app',
      streamUrl: '/api/v1/jobs/stream/job-rp-1', pollUrl: '/api/v1/jobs/job-rp-1',
    })

    let onTerminal: ((s: unknown) => void) | undefined
    mockUseJobStream.mockImplementation((_jobId, opts) => {
      if (opts.onTerminal) onTerminal = opts.onTerminal
      return { snapshot: null, status: 'idle' }
    })

    renderPage()
    await waitFor(() => expect(screen.getByLabelText(/Project/i)).toBeInTheDocument())
    await userEvent.type(screen.getByLabelText(/THANOS approval token/i), 'tok-123')
    await userEvent.click(screen.getByRole('button', { name: /^Install$/ }))

    await waitFor(() => {
      expect(mockInstallAsync).toHaveBeenCalled()
      expect(mockStartWatching).toHaveBeenCalledWith('job-rp-1', expect.stringContaining('spring-boot'))
    })
    const args = mockInstallAsync.mock.calls[0]
    expect(args[0]).toBe('ce-app')
    expect(args[1].pack.id).toBe('spring-boot')
    expect(args[2]).toBe('tok-123')

    onTerminal?.({ status: 'SUCCEEDED', result: JSON.stringify({ installed: 2, sourceTag: 'rulepack:spring-boot@1.0.0' }) })

    await waitFor(() => expect(screen.getByText(/Installed 2 conventions/)).toBeInTheDocument())
  })

  it('surfaces install error when API rejects synchronously', async () => {
    mockListProjects.mockResolvedValue([
      { id: 'p', name: 'p', language: 'java', framework: 'spring-boot', buildTool: 'gradle', lastIngested: null, description: '' },
    ])
    mockInstallAsync.mockRejectedValue(new Error('boom'))

    renderPage()
    await waitFor(() => expect(screen.getByLabelText(/Project/i)).toBeInTheDocument())
    await userEvent.click(screen.getByRole('button', { name: /^Install$/ }))

    await waitFor(() => expect(screen.getByText(/Install failed: boom/)).toBeInTheDocument())
  })

  it('surfaces install error when job terminates FAILED', async () => {
    mockListProjects.mockResolvedValue([
      { id: 'p', name: 'p', language: 'java', framework: 'spring-boot', buildTool: 'gradle', lastIngested: null, description: '' },
    ])
    mockInstallAsync.mockResolvedValue({
      jobId: 'job-rp-2', jobType: 'RULE_PACK_INSTALL', status: 'QUEUED',
      attachedToExisting: false, projectId: 'p',
      streamUrl: '/api/v1/jobs/stream/job-rp-2', pollUrl: '/api/v1/jobs/job-rp-2',
    })

    let onTerminal: ((s: unknown) => void) | undefined
    mockUseJobStream.mockImplementation((_jobId, opts) => {
      if (opts.onTerminal) onTerminal = opts.onTerminal
      return { snapshot: null, status: 'idle' }
    })

    renderPage()
    await waitFor(() => expect(screen.getByLabelText(/Project/i)).toBeInTheDocument())
    await userEvent.click(screen.getByRole('button', { name: /^Install$/ }))

    await waitFor(() => expect(mockInstallAsync).toHaveBeenCalled())
    onTerminal?.({ status: 'FAILED', errorMessage: 'pack rejected by Neo4j' })

    await waitFor(() => expect(screen.getByText(/pack rejected by Neo4j/)).toBeInTheDocument())
  })
})
