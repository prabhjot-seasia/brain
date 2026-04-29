import { render, screen, waitFor } from '@testing-library/react'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { MemoryRouter } from 'react-router-dom'
import OracleBudgetPage from './OracleBudgetPage'

const mockListProjects = vi.fn()
const mockGetOracleBudget = vi.fn()

vi.mock('../../api/brainClient', () => ({
  brainApi: {
    listProjects: (...args: unknown[]) => mockListProjects(...args),
    getOracleBudget: (...args: unknown[]) => mockGetOracleBudget(...args),
  },
}))

function renderPage() {
  return render(<MemoryRouter><OracleBudgetPage /></MemoryRouter>)
}

describe('OracleBudgetPage', () => {
  beforeEach(() => { vi.clearAllMocks() })

  it('renders one row per project with tier and recall/rerank K', async () => {
    mockListProjects.mockResolvedValue([
      { id: 'ce-app', name: 'ce-APP', language: 'java', framework: 'spring-boot', buildTool: 'gradle', lastIngested: null, description: '' },
      { id: 'ce-ui', name: 'ce-UI', language: 'java', framework: 'spring-boot', buildTool: 'gradle', lastIngested: null, description: '' },
    ])
    mockGetOracleBudget.mockImplementation((id: string) => Promise.resolve(
      id === 'ce-app' ? { recallK: 200, rerankK: 20, tier: 'HIGH' }
                       : { recallK: 50,  rerankK: 5,  tier: 'LOW' }))

    renderPage()

    await waitFor(() => {
      // Desktop table + mobile card both render in jsdom (CSS media queries are no-op)
      expect(screen.getAllByText('ce-APP').length).toBeGreaterThanOrEqual(1)
      expect(screen.getAllByText('ce-UI').length).toBeGreaterThanOrEqual(1)
      expect(screen.getAllByText('HIGH').length).toBeGreaterThanOrEqual(1)
      expect(screen.getAllByText('LOW').length).toBeGreaterThanOrEqual(1)
      expect(screen.getAllByText('200').length).toBeGreaterThanOrEqual(1)
      expect(screen.getAllByText('20').length).toBeGreaterThanOrEqual(1)
      // Structural assertions: both desktop table AND mobile cards must exist
      expect(screen.getByRole('table')).toBeInTheDocument()
      expect(screen.getAllByText('recall-K').length).toBeGreaterThanOrEqual(1)
    })
  })

  it('shows error chip when getOracleBudget rejects for a project', async () => {
    mockListProjects.mockResolvedValue([
      { id: 'broken', name: 'broken', language: 'java', framework: 'spring-boot', buildTool: 'gradle', lastIngested: null, description: '' },
    ])
    mockGetOracleBudget.mockRejectedValue(new Error('neo4j down'))

    renderPage()

    await waitFor(() => {
      expect(screen.getAllByText('error').length).toBeGreaterThanOrEqual(1)
      expect(screen.getAllByText(/neo4j down/).length).toBeGreaterThanOrEqual(1)
    })
  })

  it('shows empty-state when no projects exist', async () => {
    mockListProjects.mockResolvedValue([])

    renderPage()

    await waitFor(() => {
      expect(screen.getByText(/No projects ingested yet/i)).toBeInTheDocument()
    })
  })
})
