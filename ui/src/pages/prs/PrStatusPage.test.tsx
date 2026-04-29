import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { MemoryRouter } from 'react-router-dom'
import PrStatusPage from './PrStatusPage'
import type { PrRecordDto } from '../../api/brainClient'

const mockListPrs = vi.fn()
const mockCreatePr = vi.fn()
const mockGetPrReviews = vi.fn()

vi.mock('../../api/brainClient', () => ({
  brainApi: {
    listPrs: (...args: unknown[]) => mockListPrs(...args),
    createPr: (...args: unknown[]) => mockCreatePr(...args),
    getPrReviews: (...args: unknown[]) => mockGetPrReviews(...args),
  },
}))

function renderPage() {
  return render(
    <MemoryRouter>
      <PrStatusPage />
    </MemoryRouter>
  )
}

const samplePr: PrRecordDto = {
  id: 'pr-1',
  sessionId: 'sess-1',
  repoUrl: 'https://github.com/owner/repo',
  baseBranch: 'main',
  branchName: 'brain/codegen-123',
  prNumber: 42,
  prUrl: 'https://github.com/owner/repo/pull/42',
  status: 'CREATED',
  generatedFiles: { 'src/Foo.java': 'code' },
  selfReviewIterations: 1,
  errorMessage: null,
  createdAt: '2026-04-14T10:00:00Z',
  updatedAt: '2026-04-14T10:01:00Z',
}

describe('PrStatusPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('renders heading and empty state', async () => {
    mockListPrs.mockResolvedValue([])
    renderPage()
    await waitFor(() => {
      expect(screen.getByText('Pull Requests')).toBeInTheDocument()
      expect(screen.getByText(/No pull requests yet/)).toBeInTheDocument()
    })
  })

  it('renders PR cards with details', async () => {
    mockListPrs.mockResolvedValue([samplePr])
    renderPage()
    await waitFor(() => {
      expect(screen.getByText('CREATED')).toBeInTheDocument()
      expect(screen.getByText('#42')).toBeInTheDocument()
      expect(screen.getByText(/owner\/repo/)).toBeInTheDocument()
      expect(screen.getByText('View on GitHub')).toBeInTheDocument()
    })
  })

  it('shows error state when API fails', async () => {
    mockListPrs.mockRejectedValue(new Error('fail'))
    renderPage()
    await waitFor(() => {
      expect(screen.getByText('Failed to load pull requests.')).toBeInTheDocument()
    })
  })

  it('opens create dialog and submits', async () => {
    mockListPrs.mockResolvedValue([])
    mockCreatePr.mockResolvedValue(samplePr)
    renderPage()

    await waitFor(() => expect(screen.getByText('Pull Requests')).toBeInTheDocument())

    await userEvent.click(screen.getByRole('button', { name: /Create PR/i }))
    expect(screen.getByText('Create Pull Request')).toBeInTheDocument()

    await userEvent.type(screen.getByLabelText(/Session ID/i), 'sess-uuid')
    await userEvent.type(screen.getByLabelText(/Repository URL/i), 'https://github.com/o/r')

    const createButton = screen.getByRole('button', { name: /Create Draft PR/i })
    expect(createButton).toBeEnabled()
  })

  it('shows error message on FAILED PR', async () => {
    const failedPr: PrRecordDto = {
      ...samplePr,
      status: 'FAILED',
      prNumber: null,
      prUrl: null,
      errorMessage: 'LLM generation failed',
    }
    mockListPrs.mockResolvedValue([failedPr])
    renderPage()
    await waitFor(() => {
      expect(screen.getByText('FAILED')).toBeInTheDocument()
      expect(screen.getByText('LLM generation failed')).toBeInTheDocument()
    })
  })

  it('shows generated files count in accordion', async () => {
    mockListPrs.mockResolvedValue([samplePr])
    renderPage()
    await waitFor(() => {
      expect(screen.getByText('1 Generated Files')).toBeInTheDocument()
    })
  })
})
