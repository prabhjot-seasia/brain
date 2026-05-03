import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { MemoryRouter } from 'react-router-dom'
import FullDocsPage from './FullDocsPage'

const mockListProjects = vi.fn()
const mockGenerate = vi.fn()
const mockGetStatus = vi.fn()
const mockListFullDocs = vi.fn()
const mockRetry = vi.fn()
const mockDownload = vi.fn()

vi.mock('../../api/brainClient', () => ({
  brainApi: {
    listProjects: (...a: unknown[]) => mockListProjects(...a),
    generateFullDocs: (...a: unknown[]) => mockGenerate(...a),
    getFullDocStatus: (...a: unknown[]) => mockGetStatus(...a),
    listFullDocs: (...a: unknown[]) => mockListFullDocs(...a),
    retryFailedSections: (...a: unknown[]) => mockRetry(...a),
    downloadFullDocPdf: (...a: unknown[]) => mockDownload(...a),
  },
}))

function renderPage() {
  return render(<MemoryRouter><FullDocsPage /></MemoryRouter>)
}

const sampleProject = { id: 'ce-app', name: 'ce-APP', language: 'java', framework: 'spring-boot', buildTool: 'gradle', lastIngested: null, description: '' }

describe('FullDocsPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockListProjects.mockResolvedValue([sampleProject])
    mockListFullDocs.mockResolvedValue([])
  })
  afterEach(() => vi.useRealTimers())

  it('renders project picker + Generate button + empty history copy', async () => {
    renderPage()
    await waitFor(() => expect(screen.getByLabelText(/Project/i)).toBeInTheDocument())
    expect(screen.getByRole('button', { name: /^Generate$/i })).toBeInTheDocument()
    expect(screen.getByText(/No documentation yet\./i)).toBeInTheDocument()
  })

  it('clicking Generate calls API, shows section table, then Download appears on COMPLETED', async () => {
    mockGenerate.mockResolvedValue({ documentId: 'doc-1', status: 'GENERATING', fromCache: false })
    mockGetStatus.mockResolvedValue({
      id: 'doc-1', projectId: 'ce-app', status: 'COMPLETED',
      sectionResults: { ARCHITECTURE: 'OK', SEQUENCE_DIAGRAM: 'OK', CLASS_DIAGRAM: 'OK', FLOW_DIAGRAM: 'OK', EXPLANATION: 'OK' },
      generatedAt: new Date().toISOString(), fromCache: false,
    })

    renderPage()
    await waitFor(() => expect(screen.getByLabelText(/Project/i)).toBeInTheDocument())
    await userEvent.click(screen.getByRole('button', { name: /^Generate$/i }))

    await waitFor(() => {
      expect(screen.getAllByText(/ARCHITECTURE/).length).toBeGreaterThan(0)
      expect(screen.getAllByText(/^OK$/).length).toBeGreaterThan(0)
      expect(screen.getByRole('button', { name: /Download Full PDF/i })).toBeInTheDocument()
    })
    expect(mockGenerate).toHaveBeenCalledWith('ce-app', false)
  })

  it('PARTIAL status shows Retry button + warning alert', async () => {
    mockGenerate.mockResolvedValue({ documentId: 'doc-2', status: 'PARTIAL', fromCache: false })
    mockGetStatus.mockResolvedValue({
      id: 'doc-2', projectId: 'ce-app', status: 'PARTIAL',
      sectionResults: { ARCHITECTURE: 'FAILED', SEQUENCE_DIAGRAM: 'OK', CLASS_DIAGRAM: 'OK', FLOW_DIAGRAM: 'OK', EXPLANATION: 'OK' },
      generatedAt: new Date().toISOString(), fromCache: false,
    })

    renderPage()
    await waitFor(() => expect(screen.getByLabelText(/Project/i)).toBeInTheDocument())
    await userEvent.click(screen.getByRole('button', { name: /^Generate$/i }))

    await waitFor(() => {
      expect(screen.getByText(/1 of 5 sections failed/)).toBeInTheDocument()
      expect(screen.getByRole('button', { name: /Retry failed sections/i })).toBeInTheDocument()
    })
  })

  it('cache-hit shows "From cache" provenance badge', async () => {
    mockGenerate.mockResolvedValue({ documentId: 'doc-3', status: 'COMPLETED', fromCache: true,
      generatedAt: new Date(Date.now() - 5 * 60 * 1000).toISOString() })
    mockGetStatus.mockResolvedValue({
      id: 'doc-3', projectId: 'ce-app', status: 'COMPLETED',
      sectionResults: { ARCHITECTURE: 'OK', SEQUENCE_DIAGRAM: 'OK', CLASS_DIAGRAM: 'OK', FLOW_DIAGRAM: 'OK', EXPLANATION: 'OK' },
      generatedAt: new Date(Date.now() - 5 * 60 * 1000).toISOString(),
      fromCache: false,
    })

    renderPage()
    await waitFor(() => expect(screen.getByLabelText(/Project/i)).toBeInTheDocument())
    await userEvent.click(screen.getByRole('button', { name: /^Generate$/i }))

    await waitFor(() => {
      expect(screen.getByText(/From cache/i)).toBeInTheDocument()
    })
  })

  it('FAILED status surfaces error alert', async () => {
    mockGenerate.mockResolvedValue({ documentId: 'doc-4', status: 'FAILED', fromCache: false })
    mockGetStatus.mockResolvedValue({
      id: 'doc-4', projectId: 'ce-app', status: 'FAILED',
      sectionResults: {}, fromCache: false,
      error: 'LLM unreachable',
    })

    renderPage()
    await waitFor(() => expect(screen.getByLabelText(/Project/i)).toBeInTheDocument())
    await userEvent.click(screen.getByRole('button', { name: /^Generate$/i }))

    await waitFor(() => {
      expect(screen.getByText(/LLM unreachable/)).toBeInTheDocument()
    })
  })
})
