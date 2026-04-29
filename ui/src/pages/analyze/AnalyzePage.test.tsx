import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { MemoryRouter } from 'react-router-dom'
import AnalyzePage from './AnalyzePage'
import type { AnalyzeResponse } from '../../api/brainClient'

const mockAnalyze = vi.fn()
const mockListProjects = vi.fn()

vi.mock('../../api/brainClient', () => ({
  brainApi: {
    listProjects: (...args: unknown[]) => mockListProjects(...args),
    analyze: (...args: unknown[]) => mockAnalyze(...args),
  },
}))

function renderPage(searchParams = '') {
  return render(
    <MemoryRouter initialEntries={[`/analyze${searchParams}`]}>
      <AnalyzePage />
    </MemoryRouter>
  )
}

describe('AnalyzePage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockListProjects.mockResolvedValue([
      { id: 'ce-imei', name: 'ce-IMEI' },
      { id: 'brain', name: 'Project Brain' },
    ])
  })

  it('renders the requirement textarea and analyze button', async () => {
    renderPage()
    await waitFor(() => {
      expect(screen.getByLabelText(/Requirement/i)).toBeInTheDocument()
      expect(screen.getByRole('button', { name: /Analyze/i })).toBeInTheDocument()
    })
  })

  it('renders the project selector with loaded projects', async () => {
    renderPage()
    await waitFor(() => {
      expect(screen.getByLabelText(/Project/i)).toBeInTheDocument()
    })
  })

  it('shows error when submitting without requirement', async () => {
    renderPage()
    await waitFor(() => expect(screen.getByRole('button', { name: /Analyze/i })).toBeInTheDocument())
    await userEvent.click(screen.getByRole('button', { name: /Analyze/i }))
    expect(screen.getByText(/Enter a requirement/i)).toBeInTheDocument()
  })

  it('submits analysis and shows clarification questions', async () => {
    const clarificationResponse: AnalyzeResponse = {
      sessionId: 'sess-1',
      planReady: false,
      questions: [{text: 'Which module should this go in?', options: ['API', 'Service']}, {text: 'What is the expected throughput?', options: ['Low', 'High']}],
      unknownReferences: [],
      dimensions: {
        why: { score: 0.8, summary: 'clear' },
        what: { score: 0.6, summary: 'somewhat clear' },
        where: { score: 0.3, summary: 'unclear' },
        how: { score: 0.5, summary: 'some ideas' },
      },
    }
    mockAnalyze.mockResolvedValue(clarificationResponse)

    renderPage('?projectId=ce-imei')
    await waitFor(() => expect(screen.getByRole('button', { name: /Analyze/i })).toBeInTheDocument())

    const textarea = screen.getByLabelText(/Enter your requirement/i)
    await userEvent.type(textarea, 'Add rate limiting')
    await userEvent.click(screen.getByRole('button', { name: /Analyze/i }))

    await waitFor(() => {
      expect(screen.getByText(/Brain needs clarification/i)).toBeInTheDocument()
      expect(screen.getByText(/Which module should this go in/i)).toBeInTheDocument()
      expect(screen.getByText(/What is the expected throughput/i)).toBeInTheDocument()
    })

    expect(screen.getByText(/WHY 80%/i)).toBeInTheDocument()
    expect(screen.getByText(/WHERE 30%/i)).toBeInTheDocument()
  })

  it('submits answers in clarification round', async () => {
    const clarificationResponse: AnalyzeResponse = {
      sessionId: 'sess-1',
      planReady: false,
      questions: [{ text: 'Which module?', options: ['API', 'Service'] }],
      unknownReferences: [],
      dimensions: {
        why: { score: 0.8, summary: 'ok' },
        what: { score: 0.7, summary: 'ok' },
        where: { score: 0.3, summary: '?' },
        how: { score: 0.6, summary: 'ok' },
      },
    }

    const planResponse: AnalyzeResponse = {
      sessionId: 'sess-1',
      planReady: true,
      plan: '{"requirement":"Add rate limiting","steps":[{"order":1,"description":"Add filter","convention":"Follow Spring conventions","files":["ApiFilter.java"]}],"affectedFiles":[{"path":"ApiFilter.java","reason":"new","confidence":0.9}],"risks":[],"conventionsApplied":[],"understanding":{"why":"performance","what":"rate limiter","where":"api layer","how":"filter chain"}}',
    }

    mockAnalyze
      .mockResolvedValueOnce(clarificationResponse)
      .mockResolvedValueOnce(planResponse)

    renderPage('?projectId=ce-imei')
    await waitFor(() => expect(screen.getByRole('button', { name: /Analyze/i })).toBeInTheDocument())

    await userEvent.type(screen.getByLabelText(/Enter your requirement/i), 'Add rate limiting')
    await userEvent.click(screen.getByRole('button', { name: /Analyze/i }))

    await waitFor(() => expect(screen.getByText(/Which module/i)).toBeInTheDocument())

    const answerField = screen.getByLabelText(/Your answers/i)
    await userEvent.type(answerField, 'The API gateway module')
    await userEvent.click(screen.getByRole('button', { name: /Submit Round/i }))

    await waitFor(() => {
      expect(screen.getByText(/Implementation Plan Ready/i)).toBeInTheDocument()
    })

    expect(mockAnalyze).toHaveBeenCalledTimes(2)
    expect(mockAnalyze).toHaveBeenLastCalledWith(expect.objectContaining({
      sessionId: 'sess-1',
      answers: 'The API gateway module',
    }))
  })

  it('displays structured plan with affected files and steps', async () => {
    const planResponse: AnalyzeResponse = {
      sessionId: 'sess-1',
      planReady: true,
      plan: JSON.stringify({
        requirement: 'Add auth',
        understanding: { why: 'security', what: 'JWT auth', where: 'api', how: 'Spring Security' },
        affectedFiles: [
          { path: 'SecurityConfig.java', reason: 'new config', confidence: 0.95 },
          { path: 'AuthFilter.java', reason: 'new filter', confidence: 0.8 },
        ],
        steps: [
          { order: 1, description: 'Create SecurityConfig', convention: 'Use @Configuration', files: ['SecurityConfig.java'] },
          { order: 2, description: 'Add AuthFilter', convention: null, files: ['AuthFilter.java'] },
        ],
        risks: [{ description: 'Token expiry edge case', severity: 'medium', files: ['AuthFilter.java'] }],
        conventionsApplied: [{ rule: 'Use constructor injection', source: 'CONTRIBUTING.md' }],
        assumptions: [],
      }),
    }
    mockAnalyze.mockResolvedValue(planResponse)

    renderPage('?projectId=ce-imei')
    await waitFor(() => expect(screen.getByRole('button', { name: /Analyze/i })).toBeInTheDocument())

    await userEvent.type(screen.getByLabelText(/Enter your requirement/i), 'Add auth')
    await userEvent.click(screen.getByRole('button', { name: /Analyze/i }))

    await waitFor(() => {
      expect(screen.getByText(/Implementation Plan Ready/i)).toBeInTheDocument()
    })

    expect(screen.getByText(/SecurityConfig.java/i)).toBeInTheDocument()
    expect(screen.getByText(/Affected Files/i)).toBeInTheDocument()
    expect(screen.getByText(/Implementation Steps/i)).toBeInTheDocument()
    expect(screen.getByText(/Create SecurityConfig/i)).toBeInTheDocument()
  })

  it('displays raw plan when JSON parsing fails', async () => {
    const planResponse: AnalyzeResponse = {
      sessionId: 'sess-1',
      planReady: true,
      plan: 'This is a plain text plan that is not JSON',
    }
    mockAnalyze.mockResolvedValue(planResponse)

    renderPage('?projectId=ce-imei')
    await waitFor(() => expect(screen.getByRole('button', { name: /Analyze/i })).toBeInTheDocument())

    await userEvent.type(screen.getByLabelText(/Enter your requirement/i), 'Explain project')
    await userEvent.click(screen.getByRole('button', { name: /Analyze/i }))

    await waitFor(() => {
      expect(screen.getByText(/Implementation Plan Ready/i)).toBeInTheDocument()
      expect(screen.getByText(/This is a plain text plan/i)).toBeInTheDocument()
    })
  })

  it('shows unknown references warning', async () => {
    const response: AnalyzeResponse = {
      sessionId: 'sess-1',
      planReady: false,
      questions: [{ text: 'What is that service?', options: ['REST API', 'gRPC'] }],
      unknownReferences: ['alpha-svc'],
      dimensions: {
        why: { score: 0.9, summary: 'ok' },
        what: { score: 0.9, summary: 'ok' },
        where: { score: 0.9, summary: 'ok' },
        how: { score: 0.9, summary: 'ok' },
      },
    }
    mockAnalyze.mockResolvedValue(response)

    renderPage('?projectId=ce-imei')
    await waitFor(() => expect(screen.getByRole('button', { name: /Analyze/i })).toBeInTheDocument())

    await userEvent.type(screen.getByLabelText(/Enter your requirement/i), 'Add integration')
    await userEvent.click(screen.getByRole('button', { name: /Analyze/i }))

    await waitFor(() => {
      expect(screen.getByText(/Unknown references/i)).toBeInTheDocument()
      expect(screen.getByText(/alpha-svc/i)).toBeInTheDocument()
    })
  })

  it('shows error alert when API call fails', async () => {
    mockAnalyze.mockRejectedValue(new Error('Network error'))

    renderPage('?projectId=ce-imei')
    await waitFor(() => expect(screen.getByRole('button', { name: /Analyze/i })).toBeInTheDocument())

    await userEvent.type(screen.getByLabelText(/Enter your requirement/i), 'Test')
    await userEvent.click(screen.getByRole('button', { name: /Analyze/i }))

    await waitFor(() => {
      expect(screen.getByText(/Analysis failed/i)).toBeInTheDocument()
    })
  })

  it('resets state when Start Over is clicked', async () => {
    const clarificationResponse: AnalyzeResponse = {
      sessionId: 'sess-1',
      planReady: false,
      questions: [{ text: 'Q1', options: ['Yes', 'No'] }],
      unknownReferences: [],
      dimensions: {
        why: { score: 0.5, summary: 'ok' },
        what: { score: 0.5, summary: 'ok' },
        where: { score: 0.5, summary: 'ok' },
        how: { score: 0.5, summary: 'ok' },
      },
    }
    mockAnalyze.mockResolvedValue(clarificationResponse)

    renderPage('?projectId=ce-imei')
    await waitFor(() => expect(screen.getByRole('button', { name: /Analyze/i })).toBeInTheDocument())

    await userEvent.type(screen.getByLabelText(/Enter your requirement/i), 'Test')
    await userEvent.click(screen.getByRole('button', { name: /Analyze/i }))

    await waitFor(() => expect(screen.getByText(/Brain needs clarification/i)).toBeInTheDocument())

    await userEvent.click(screen.getByRole('button', { name: /Start Over/i }))

    expect(screen.queryByText(/Brain needs clarification/i)).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Analyze/i })).toBeInTheDocument()
  })

  it('renders generic JSON plan when keys do not match structured schema', async () => {
    const planResponse: AnalyzeResponse = {
      sessionId: 'sess-1',
      planReady: true,
      plan: JSON.stringify({
        summary: 'Validation feature',
        components: ['Validator', 'Controller'],
        priority: 'high',
      }),
    }
    mockAnalyze.mockResolvedValue(planResponse)

    renderPage('?projectId=ce-imei')
    await waitFor(() => expect(screen.getByRole('button', { name: /Analyze/i })).toBeInTheDocument())
    await userEvent.type(screen.getByLabelText(/Enter your requirement/i), 'Add val')
    await userEvent.click(screen.getByRole('button', { name: /Analyze/i }))

    await waitFor(() => {
      expect(screen.getByText(/Implementation Plan Ready/i)).toBeInTheDocument()
    })
    expect(screen.getByText(/Validation feature/i)).toBeInTheDocument()
    expect(screen.getByText(/Validator/i)).toBeInTheDocument()
  })

  it('renders markdown plan with headings and lists', async () => {
    const planResponse: AnalyzeResponse = {
      sessionId: 'sess-1',
      planReady: true,
      plan: '## Overview\nAdd rate limiting to the API.\n\n- Step 1: Create filter\n- Step 2: Configure limits\n\n### Details\n1. Use bucket algorithm',
    }
    mockAnalyze.mockResolvedValue(planResponse)

    renderPage('?projectId=ce-imei')
    await waitFor(() => expect(screen.getByRole('button', { name: /Analyze/i })).toBeInTheDocument())
    await userEvent.type(screen.getByLabelText(/Enter your requirement/i), 'Rate limit')
    await userEvent.click(screen.getByRole('button', { name: /Analyze/i }))

    await waitFor(() => {
      expect(screen.getByText(/Implementation Plan Ready/i)).toBeInTheDocument()
    })
    expect(screen.getByText(/Overview/i)).toBeInTheDocument()
    expect(screen.getByText(/Create filter/i)).toBeInTheDocument()
  })

  it('renders plan with alternate JSON key names', async () => {
    const planResponse: AnalyzeResponse = {
      sessionId: 'sess-1',
      planReady: true,
      plan: JSON.stringify({
        affected_files: [{ file: 'UserService.java', rationale: 'needs update', confidence: 0.85 }],
        implementation_steps: [{ step: 1, action: 'Modify service', rule: 'DI convention' }],
        concerns: [{ risk: 'Migration needed', severity: 'high' }],
      }),
    }
    mockAnalyze.mockResolvedValue(planResponse)

    renderPage('?projectId=ce-imei')
    await waitFor(() => expect(screen.getByRole('button', { name: /Analyze/i })).toBeInTheDocument())
    await userEvent.type(screen.getByLabelText(/Enter your requirement/i), 'Refactor')
    await userEvent.click(screen.getByRole('button', { name: /Analyze/i }))

    await waitFor(() => {
      expect(screen.getByText(/Implementation Plan Ready/i)).toBeInTheDocument()
    })
    expect(screen.getByText(/UserService.java/i)).toBeInTheDocument()
    expect(screen.getByText(/Modify service/i)).toBeInTheDocument()
    expect(screen.getByText(/Migration needed/i)).toBeInTheDocument()
  })

  it('shows auto-detected project when no project selected', async () => {
    const response: AnalyzeResponse = {
      sessionId: 'sess-1',
      planReady: false,
      questions: [{ text: 'Q1', options: ['Yes', 'No'] }],
      unknownReferences: [],
      dimensions: { why: { score: 0.5, summary: 'ok' }, what: { score: 0.5, summary: 'ok' }, where: { score: 0.5, summary: 'ok' }, how: { score: 0.5, summary: 'ok' } },
      affectedProjects: [{ projectId: 'ce-imei', confidence: 0.9, rationale: 'matches' }],
    }
    mockAnalyze.mockResolvedValue(response)

    renderPage()
    await waitFor(() => expect(screen.getByRole('button', { name: /Analyze/i })).toBeInTheDocument())
    await userEvent.type(screen.getByLabelText(/Enter your requirement/i), 'Add feature')
    await userEvent.click(screen.getByRole('button', { name: /Analyze/i }))

    await waitFor(() => {
      expect(screen.getByText(/Auto-detected project/i)).toBeInTheDocument()
      expect(screen.getByText(/ce-imei/i)).toBeInTheDocument()
    })
  })

  it('New Analysis button on plan card resets state', async () => {
    const planResponse: AnalyzeResponse = {
      sessionId: 'sess-1',
      planReady: true,
      plan: '{"requirement":"test","steps":[],"affectedFiles":[],"risks":[],"conventionsApplied":[],"understanding":{"why":"a","what":"b","where":"c","how":"d"}}',
    }
    mockAnalyze.mockResolvedValue(planResponse)

    renderPage('?projectId=ce-imei')
    await waitFor(() => expect(screen.getByRole('button', { name: /Analyze/i })).toBeInTheDocument())

    await userEvent.type(screen.getByLabelText(/Enter your requirement/i), 'Test')
    await userEvent.click(screen.getByRole('button', { name: /Analyze/i }))

    await waitFor(() => expect(screen.getByText(/Implementation Plan Ready/i)).toBeInTheDocument())

    await userEvent.click(screen.getByRole('button', { name: /New Analysis/i }))
    expect(screen.queryByText(/Implementation Plan Ready/i)).not.toBeInTheDocument()
  })
})
