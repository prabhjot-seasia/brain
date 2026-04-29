import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import IngestPage from './IngestPage'

const mockIngest = vi.fn()
const mockGetProject = vi.fn()
const mockUseJobStream = vi.fn()
const mockStartWatching = vi.fn()

vi.mock('../../api/brainClient', () => ({
  brainApi: {
    ingest: (...args: unknown[]) => mockIngest(...args),
    getProject: (...args: unknown[]) => mockGetProject(...args),
  },
}))

vi.mock('../../hooks/useJobStream', () => ({
  useJobStream: (jobId: string | null, opts: { onTerminal?: (s: unknown) => void } = {}) => {
    return mockUseJobStream(jobId, opts)
  },
}))

vi.mock('../../components/widgets', () => ({
  startWatchingJob: (...args: unknown[]) => mockStartWatching(...args),
}))

const startResponse = {
  jobId: 'job-1', jobType: 'INGEST_PROJECT', status: 'QUEUED',
  attachedToExisting: false, streamUrl: '/api/v1/jobs/stream/job-1', pollUrl: '/api/v1/jobs/job-1',
}

describe('IngestPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockIngest.mockResolvedValue(startResponse)
    mockGetProject.mockResolvedValue({ language: 'java', framework: 'spring-boot', buildTool: 'gradle' })
    mockUseJobStream.mockReturnValue({ snapshot: null, status: 'idle' })
  })

  it('renders the form with required fields', () => {
    render(<IngestPage />)
    expect(screen.getByLabelText(/Project ID/i)).toBeInTheDocument()
    expect(screen.getByLabelText(/Project Name/i)).toBeInTheDocument()
    expect(screen.getByLabelText(/GitHub URL/i)).toBeInTheDocument()
    expect(screen.getByLabelText(/Branch/i)).toBeInTheDocument()
    expect(screen.getByLabelText(/Description/i)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Start Ingestion/i })).toBeInTheDocument()
  })

  it('shows error when submitting without required fields', async () => {
    render(<IngestPage />)
    await userEvent.click(screen.getByRole('button', { name: /Start Ingestion/i }))
    expect(screen.getByText(/Project ID, Name, and GitHub URL are required/i)).toBeInTheDocument()
  })

  it('submits form and registers job for watching', async () => {
    render(<IngestPage />)
    await userEvent.type(screen.getByLabelText(/Project ID/i), 'test')
    await userEvent.type(screen.getByLabelText(/Project Name/i), 'Test')
    await userEvent.type(screen.getByLabelText(/GitHub URL/i), 'https://github.com/org/repo.git')
    await userEvent.click(screen.getByRole('button', { name: /Start Ingestion/i }))

    await waitFor(() => {
      expect(mockIngest).toHaveBeenCalledWith(expect.objectContaining({
        projectId: 'test', projectName: 'Test', repoUrl: 'https://github.com/org/repo.git',
      }))
      expect(mockStartWatching).toHaveBeenCalledWith('job-1', 'Ingest test')
    })
  })

  it('shows ingesting state from RUNNING snapshot', () => {
    mockUseJobStream.mockReturnValue({
      snapshot: { id: 'job-1', status: 'RUNNING', progressPct: 40, progressMsg: 'Embedding chunks…' },
      status: 'open',
    })
    render(<IngestPage />)
    expect(screen.getByText(/Embedding chunks/i)).toBeInTheDocument()
  })

  it('shows success state from SUCCEEDED snapshot', () => {
    mockUseJobStream.mockReturnValue({
      snapshot: { id: 'job-1', status: 'SUCCEEDED' },
      status: 'closed',
    })
    render(<IngestPage />)
    expect(screen.getByText(/Ingestion complete/i)).toBeInTheDocument()
  })

  it('shows failure state from FAILED snapshot', () => {
    mockUseJobStream.mockReturnValue({
      snapshot: { id: 'job-1', status: 'FAILED', errorMessage: 'Clone failed: repo not found' },
      status: 'closed',
    })
    render(<IngestPage />)
    expect(screen.getByText(/Ingestion failed/i)).toBeInTheDocument()
    expect(screen.getByText(/Clone failed: repo not found/i)).toBeInTheDocument()
  })

  it('shows API error when ingest call itself fails', async () => {
    mockIngest.mockRejectedValue(new Error('Network error'))
    render(<IngestPage />)
    await userEvent.type(screen.getByLabelText(/Project ID/i), 'test')
    await userEvent.type(screen.getByLabelText(/Project Name/i), 'Test')
    await userEvent.type(screen.getByLabelText(/GitHub URL/i), 'https://github.com/org/repo.git')
    await userEvent.click(screen.getByRole('button', { name: /Start Ingestion/i }))

    await waitFor(() => {
      expect(screen.getByText(/Ingestion failed.*Brain API/i)).toBeInTheDocument()
    })
  })

  it('shows joined-existing message when attachedToExisting=true', async () => {
    mockIngest.mockResolvedValue({ ...startResponse, attachedToExisting: true })
    render(<IngestPage />)
    await userEvent.type(screen.getByLabelText(/Project ID/i), 'test')
    await userEvent.type(screen.getByLabelText(/Project Name/i), 'Test')
    await userEvent.type(screen.getByLabelText(/GitHub URL/i), 'https://github.com/org/repo.git')

    mockUseJobStream.mockReturnValue({
      snapshot: { id: 'job-1', status: 'RUNNING' }, status: 'open',
    })
    await userEvent.click(screen.getByRole('button', { name: /Start Ingestion/i }))

    await waitFor(() => {
      expect(screen.getByText(/joined the in-flight run/i)).toBeInTheDocument()
    })
  })

  it('sends branch when provided', async () => {
    render(<IngestPage />)
    await userEvent.type(screen.getByLabelText(/Project ID/i), 'test')
    await userEvent.type(screen.getByLabelText(/Project Name/i), 'Test')
    await userEvent.type(screen.getByLabelText(/GitHub URL/i), 'https://github.com/org/repo.git')
    await userEvent.type(screen.getByLabelText(/Branch/i), 'develop')
    await userEvent.click(screen.getByRole('button', { name: /Start Ingestion/i }))

    expect(mockIngest).toHaveBeenCalledWith(expect.objectContaining({ branch: 'develop' }))
  })
})
