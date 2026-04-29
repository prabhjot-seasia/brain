import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { MemoryRouter } from 'react-router-dom'
import LearningDashboardPage from './LearningDashboardPage'
import type { LearningEventDto } from '../../api/brainClient'

const mockGetLearningEvents = vi.fn()
const mockListProjects = vi.fn()

vi.mock('../../api/brainClient', () => ({
  brainApi: {
    getLearningEvents: (...args: unknown[]) => mockGetLearningEvents(...args),
    listProjects: (...args: unknown[]) => mockListProjects(...args),
  },
}))

function renderPage() {
  return render(
    <MemoryRouter>
      <LearningDashboardPage />
    </MemoryRouter>
  )
}

const sampleEvent: LearningEventDto = {
  id: 'evt-1',
  prRecordId: 'pr-1',
  projectId: 'proj-1',
  eventType: 'CONVENTION_WEIGHT_ADJUSTED',
  conventionRule: 'Use constructor injection',
  oldWeight: 1.0,
  newWeight: 1.1,
  details: { reason: 'Convention followed in merged PR', delta: 0.1 },
  createdAt: '2026-04-14T10:00:00Z',
}

describe('LearningDashboardPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockListProjects.mockResolvedValue([])
  })

  it('renders heading and empty state', async () => {
    mockGetLearningEvents.mockResolvedValue([])
    renderPage()
    await waitFor(() => {
      expect(screen.getByText('Learning Dashboard')).toBeInTheDocument()
      expect(screen.getByText(/No learning events yet/)).toBeInTheDocument()
    })
  })

  it('renders learning event cards', async () => {
    mockGetLearningEvents.mockResolvedValue([sampleEvent])
    renderPage()
    await waitFor(() => {
      expect(screen.getByText('Weight Adjusted')).toBeInTheDocument()
      expect(screen.getByText('Use constructor injection')).toBeInTheDocument()
      expect(screen.getByText(/1\.00 → 1\.10/)).toBeInTheDocument()
    })
  })

  it('shows error state when API fails', async () => {
    mockGetLearningEvents.mockRejectedValue(new Error('fail'))
    renderPage()
    await waitFor(() => {
      expect(screen.getByText('Failed to load learning events.')).toBeInTheDocument()
    })
  })

  it('shows summary chips with counts', async () => {
    const downEvent: LearningEventDto = {
      ...sampleEvent,
      id: 'evt-2',
      oldWeight: 1.5,
      newWeight: 1.4,
    }
    mockGetLearningEvents.mockResolvedValue([sampleEvent, downEvent])
    renderPage()
    await waitFor(() => {
      expect(screen.getByText('2 Events')).toBeInTheDocument()
      expect(screen.getByText('1 Reinforced')).toBeInTheDocument()
      expect(screen.getByText('1 Adjusted Down')).toBeInTheDocument()
    })
  })

  it('shows project filter dropdown', async () => {
    mockGetLearningEvents.mockResolvedValue([])
    mockListProjects.mockResolvedValue([{ id: 'proj-1', name: 'Test Project' }])
    renderPage()
    await waitFor(() => {
      expect(screen.getByLabelText(/Filter by Project/i)).toBeInTheDocument()
    })
  })
})
