import { render, screen, waitFor } from '@testing-library/react'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { MemoryRouter } from 'react-router-dom'
import TokenUsagePage from './TokenUsagePage'
import type { TokenUsageSummaryDto } from '../../api/brainClient'

const mockGetSummary = vi.fn()
const mockGetRecent = vi.fn()

vi.mock('../../api/brainClient', () => ({
  brainApi: {
    getTokenUsageSummary: (...args: unknown[]) => mockGetSummary(...args),
    getTokenUsageRecent: (...args: unknown[]) => mockGetRecent(...args),
  },
}))

function renderPage() {
  return render(
    <MemoryRouter>
      <TokenUsagePage />
    </MemoryRouter>
  )
}

const sampleSummary: TokenUsageSummaryDto = {
  totalCalls: 42,
  cacheHits: 15,
  cacheHitRate: 0.357,
  totalInputTokens: 12000,
  totalOutputTokens: 8000,
  totalCost: 1.85,
  breakdown: [
    { serviceName: 'PlannerService', operation: 'PLAN', inputTokens: 6000, outputTokens: 4000, callCount: 20, cost: 0.95 },
    { serviceName: 'DocGenerator', operation: 'DOC_GENERATE', inputTokens: 6000, outputTokens: 4000, callCount: 22, cost: 0.90 },
  ],
}

describe('TokenUsagePage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('renders heading and empty state', async () => {
    mockGetSummary.mockResolvedValue(null)
    mockGetRecent.mockResolvedValue([])
    renderPage()
    await waitFor(() => {
      expect(screen.getByText('Token Usage Dashboard')).toBeInTheDocument()
    })
  })

  it('renders summary cards with data', async () => {
    mockGetSummary.mockResolvedValue(sampleSummary)
    mockGetRecent.mockResolvedValue([])
    renderPage()
    await waitFor(() => {
      expect(screen.getByText('20,000')).toBeInTheDocument()
      expect(screen.getByText('$1.85')).toBeInTheDocument()
      expect(screen.getByText('36%')).toBeInTheDocument()
      expect(screen.getByText('42')).toBeInTheDocument()
    })
  })

  it('renders service breakdown', async () => {
    mockGetSummary.mockResolvedValue(sampleSummary)
    mockGetRecent.mockResolvedValue([])
    renderPage()
    await waitFor(() => {
      expect(screen.getByText('PlannerService')).toBeInTheDocument()
      expect(screen.getByText('DocGenerator')).toBeInTheDocument()
    })
  })

  it('shows error state when API fails', async () => {
    mockGetSummary.mockRejectedValue(new Error('fail'))
    mockGetRecent.mockRejectedValue(new Error('fail'))
    renderPage()
    await waitFor(() => {
      expect(screen.getByText('Failed to load token usage data.')).toBeInTheDocument()
    })
  })
})
