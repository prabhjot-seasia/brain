import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { ThemeProvider } from '@mui/material/styles'
import { assurantTheme } from '../../theme/assurantTheme'
import AutonomousDevPage from './AutonomousDevPage'
import { brainApi } from '../../api/brainClient'

vi.mock('../../api/brainClient', () => ({
  brainApi: {
    autodevStart: vi.fn(),
    autodevClarify: vi.fn(),
    autodevPlan: vi.fn(),
    autodevExecute: vi.fn(),
    autodevCreatePrs: vi.fn(),
  },
}))

const mockedBrainApi = brainApi as unknown as {
  autodevStart: ReturnType<typeof vi.fn>
  autodevClarify: ReturnType<typeof vi.fn>
  autodevPlan: ReturnType<typeof vi.fn>
  autodevExecute: ReturnType<typeof vi.fn>
  autodevCreatePrs: ReturnType<typeof vi.fn>
}

function renderPage() {
  return render(
    <ThemeProvider theme={assurantTheme}>
      <AutonomousDevPage />
    </ThemeProvider>
  )
}

describe('AutonomousDevPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('shows the intake stage on first render', () => {
    renderPage()
    expect(screen.getByText('Autonomous Development')).toBeInTheDocument()
    expect(screen.getByText('Stage 1 — Intake')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /start/i })).toBeInTheDocument()
  })

  it('rejects empty payload on Start', async () => {
    renderPage()
    fireEvent.click(screen.getByRole('button', { name: /start/i }))
    await waitFor(() => {
      expect(screen.getByText(/Paste a requirement/i)).toBeInTheDocument()
    })
  })

  it('progresses to clarification when start returns questions', async () => {
    mockedBrainApi.autodevStart.mockResolvedValue({
      sessionId: 's-1',
      intakeText: 'Do the thing',
      proposedAffectedProjects: [{ projectId: 'backend', confidence: 0.9, rationale: 'found' }],
      clarificationQuestions: ['Sync or async?'],
      planReady: false,
    })
    renderPage()
    fireEvent.change(screen.getByRole('textbox', { name: /requirement/i }), { target: { value: 'Do the thing' } })
    fireEvent.click(screen.getByRole('button', { name: /start/i }))

    await waitFor(() => {
      expect(screen.getByText('Sync or async?')).toBeInTheDocument()
      expect(screen.getByText(/backend/)).toBeInTheDocument()
    })
  })

  it('proceeds straight to plan when start returns planReady', async () => {
    mockedBrainApi.autodevStart.mockResolvedValue({
      sessionId: 's-1',
      intakeText: 'Do the thing',
      proposedAffectedProjects: [],
      clarificationQuestions: [],
      planReady: true,
    })
    renderPage()
    fireEvent.change(screen.getByRole('textbox', { name: /requirement/i }), { target: { value: 'Do the thing' } })
    fireEvent.click(screen.getByRole('button', { name: /start/i }))

    await waitFor(() => {
      expect(screen.getByRole('button', { name: /generate plan/i })).toBeInTheDocument()
    })
  })
})
