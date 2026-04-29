import { render, screen, waitFor, fireEvent } from '@testing-library/react'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { MemoryRouter } from 'react-router-dom'
import AvengerReviewPage from './AvengerReviewPage'

const mockListProjects = vi.fn()
const mockGetAvengerHistory = vi.fn()

vi.mock('../../api/brainClient', () => ({
  brainApi: {
    listProjects: (...args: unknown[]) => mockListProjects(...args),
    getAvengerHistory: (...args: unknown[]) => mockGetAvengerHistory(...args),
  },
}))

function renderPage() {
  return render(
    <MemoryRouter>
      <AvengerReviewPage />
    </MemoryRouter>
  )
}

describe('AvengerReviewPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockListProjects.mockResolvedValue([
      { id: 'proj-1', name: 'Demo', language: 'Java', framework: 'Spring Boot' },
    ])
  })

  it('renders heading and select controls', async () => {
    renderPage()
    await waitFor(() => {
      expect(screen.getByText('Avenger Reviews')).toBeInTheDocument()
    })
    expect(screen.getByLabelText('Avenger')).toBeInTheDocument()
    expect(screen.getByLabelText('Project')).toBeInTheDocument()
  })

  it('shows select-a-project hint when no project is chosen', async () => {
    renderPage()
    await waitFor(() => {
      expect(screen.getByText(/Select a project to view/)).toBeInTheDocument()
    })
  })

  it('fetches review history on project selection', async () => {
    mockGetAvengerHistory.mockResolvedValue([
      {
        id: 'rev-1',
        avenger: 'STARK',
        projectId: 'proj-1',
        requestHash: 'abc',
        verdict: 'CHANGES_REQUESTED',
        issues: ['Field injection detected'],
        summary: 'STARK found 1 issue',
        tokensIn: 0,
        tokensOut: 0,
        latencyMs: 42,
        createdAt: '2026-04-16T10:00:00Z',
      },
    ])
    renderPage()

    await waitFor(() => {
      expect(screen.getByLabelText('Project')).toBeInTheDocument()
    })

    const projectSelect = screen.getByLabelText('Project')
    fireEvent.mouseDown(projectSelect)
    await waitFor(() => {
      const option = screen.getByText(/proj-1 — Demo/)
      fireEvent.click(option)
    })

    await waitFor(() => {
      expect(mockGetAvengerHistory).toHaveBeenCalledWith('STARK', 'proj-1', 50)
      expect(screen.getByText('STARK found 1 issue')).toBeInTheDocument()
      expect(screen.getByText('Field injection detected')).toBeInTheDocument()
    })
  })

  it('shows empty state when project has no reviews', async () => {
    mockGetAvengerHistory.mockResolvedValue([])
    renderPage()

    await waitFor(() => {
      expect(screen.getByLabelText('Project')).toBeInTheDocument()
    })

    const projectSelect = screen.getByLabelText('Project')
    fireEvent.mouseDown(projectSelect)
    await waitFor(() => {
      const option = screen.getByText(/proj-1 — Demo/)
      fireEvent.click(option)
    })

    await waitFor(() => {
      expect(screen.getByText(/No STARK reviews yet for this project/)).toBeInTheDocument()
    })
  })
})
