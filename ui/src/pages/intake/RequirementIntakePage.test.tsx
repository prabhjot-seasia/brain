import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { MemoryRouter } from 'react-router-dom'
import RequirementIntakePage from './RequirementIntakePage'
import type { AnalyzeResponse, IntakeResponse } from '../../api/brainClient'

const mockListProjects = vi.fn()
const mockFetchJiraTicket = vi.fn()
const mockUploadDocument = vi.fn()
const mockSubmitAdhoc = vi.fn()
const mockAnalyze = vi.fn()

vi.mock('../../api/brainClient', () => ({
  brainApi: {
    listProjects: (...args: unknown[]) => mockListProjects(...args),
    fetchJiraTicket: (...args: unknown[]) => mockFetchJiraTicket(...args),
    uploadDocument: (...args: unknown[]) => mockUploadDocument(...args),
    submitAdhoc: (...args: unknown[]) => mockSubmitAdhoc(...args),
    analyze: (...args: unknown[]) => mockAnalyze(...args),
  },
}))

function renderPage() {
  return render(
    <MemoryRouter>
      <RequirementIntakePage />
    </MemoryRouter>
  )
}

describe('RequirementIntakePage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockListProjects.mockResolvedValue([
      { id: 'ce-imei', name: 'ce-IMEI' },
      { id: 'brain', name: 'Project Brain' },
    ])
  })

  it('renders all three intake tabs', async () => {
    renderPage()
    await waitFor(() => {
      expect(screen.getByText('Jira Ticket')).toBeInTheDocument()
      expect(screen.getByText('Upload Doc')).toBeInTheDocument()
      expect(screen.getByText('Free Text')).toBeInTheDocument()
    })
  })

  it('renders the page heading', () => {
    renderPage()
    expect(screen.getByText('Analyze Requirement')).toBeInTheDocument()
  })

  it('fetches Jira ticket and shows extracted text', async () => {
    const intakeResponse: IntakeResponse = {
      intakeId: 'int-1',
      sourceType: 'JIRA_TICKET',
      extractedText: 'Add payment retry logic\n\nRetry failed payments after 30 minutes',
      charCount: 60,
      issueKey: 'PAY-123',
      summary: 'Add payment retry logic',
    }
    mockFetchJiraTicket.mockResolvedValue(intakeResponse)

    renderPage()
    await waitFor(() => expect(screen.getByText('Jira Ticket')).toBeInTheDocument())

    const input = screen.getByLabelText(/Jira Ticket Key or URL/i)
    await userEvent.type(input, 'PAY-123')
    await userEvent.click(screen.getByRole('button', { name: /Fetch/i }))

    await waitFor(() => {
      expect(screen.getByText(/Extracted Requirement/i)).toBeInTheDocument()
      expect(screen.getByDisplayValue(/Add payment retry logic/)).toBeInTheDocument()
    })
  })

  it('submits free text and shows review screen', async () => {
    renderPage()
    await waitFor(() => expect(screen.getByText('Free Text')).toBeInTheDocument())

    await userEvent.click(screen.getByText('Free Text'))
    const textarea = screen.getByLabelText(/Describe your requirement/i)
    await userEvent.type(textarea, 'Add rate limiting to the validation endpoint')
    await userEvent.click(screen.getByRole('button', { name: /Use This/i }))

    await waitFor(() => {
      expect(screen.getByText(/Extracted Requirement/i)).toBeInTheDocument()
    })
  })

  it('shows error when Jira fetch fails', async () => {
    mockFetchJiraTicket.mockRejectedValue(new Error('Not found'))

    renderPage()
    await waitFor(() => expect(screen.getByText('Jira Ticket')).toBeInTheDocument())

    await userEvent.type(screen.getByLabelText(/Jira Ticket Key or URL/i), 'BAD-999')
    await userEvent.click(screen.getByRole('button', { name: /Fetch/i }))

    await waitFor(() => {
      expect(screen.getByText(/Failed to fetch Jira ticket/i)).toBeInTheDocument()
    })
  })

  it('starts analysis and shows clarification chat', async () => {
    const clarificationResponse: AnalyzeResponse = {
      sessionId: 'sess-1',
      planReady: false,
      questions: [{ text: 'Which module should this go in?', options: ['API gateway', 'Service layer'] }, { text: 'Sync or async?', options: ['Synchronous', 'Asynchronous'] }],
      unknownReferences: [],
      dimensions: {
        why: { score: 0.8, summary: 'clear' },
        what: { score: 0.6, summary: 'partial' },
        where: { score: 0.3, summary: 'unclear' },
        how: { score: 0.5, summary: 'some ideas' },
      },
    }
    mockAnalyze.mockResolvedValue(clarificationResponse)

    renderPage()
    await waitFor(() => expect(screen.getByText('Free Text')).toBeInTheDocument())

    await userEvent.click(screen.getByText('Free Text'))
    await userEvent.type(screen.getByLabelText(/Describe your requirement/i), 'Add caching')
    await userEvent.click(screen.getByRole('button', { name: /Use This/i }))

    await waitFor(() => expect(screen.getByText(/Extracted Requirement/i)).toBeInTheDocument())

    await userEvent.click(screen.getByLabelText(/Project/i))
    await waitFor(() => expect(screen.getByText('ce-imei — ce-IMEI')).toBeInTheDocument())
    await userEvent.click(screen.getByText('ce-imei — ce-IMEI'))

    await userEvent.click(screen.getByRole('button', { name: /Analyze/i }))

    await waitFor(() => {
      expect(screen.getByText(/Which module should this go in/i)).toBeInTheDocument()
      expect(screen.getByText(/WHY 80%/i)).toBeInTheDocument()
    })
  })

  it('submits answer in clarification chat and receives plan', async () => {
    const clarificationResponse: AnalyzeResponse = {
      sessionId: 'sess-1',
      planReady: false,
      questions: [{ text: 'Which module?', options: ['API gateway', 'Service layer'] }],
      dimensions: { why: { score: 0.8, summary: 'ok' }, what: { score: 0.7, summary: 'ok' }, where: { score: 0.3, summary: '?' }, how: { score: 0.5, summary: 'ok' } },
    }
    const planResponse: AnalyzeResponse = {
      sessionId: 'sess-1',
      planReady: true,
      plan: '{"requirement":"Add caching","steps":[]}',
    }
    mockAnalyze.mockResolvedValueOnce(clarificationResponse).mockResolvedValueOnce(planResponse)

    renderPage()
    await waitFor(() => expect(screen.getByText('Free Text')).toBeInTheDocument())

    await userEvent.click(screen.getByText('Free Text'))
    await userEvent.type(screen.getByLabelText(/Describe your requirement/i), 'Add caching')
    await userEvent.click(screen.getByRole('button', { name: /Use This/i }))

    await waitFor(() => expect(screen.getByText(/Extracted Requirement/i)).toBeInTheDocument())
    await userEvent.click(screen.getByLabelText(/Project/i))
    await waitFor(() => expect(screen.getByText('ce-imei — ce-IMEI')).toBeInTheDocument())
    await userEvent.click(screen.getByText('ce-imei — ce-IMEI'))
    await userEvent.click(screen.getByRole('button', { name: /Analyze/i }))

    await waitFor(() => expect(screen.getByText(/Which module/i)).toBeInTheDocument())

    await userEvent.click(screen.getByText('API gateway'))
    await userEvent.click(screen.getByRole('button', { name: /Submit Answers/i }))

    await waitFor(() => {
      expect(screen.getByText(/Plan Ready/i)).toBeInTheDocument()
    })
  })

  it('shows Back button to return to intake from review', async () => {
    renderPage()
    await waitFor(() => expect(screen.getByText('Free Text')).toBeInTheDocument())

    await userEvent.click(screen.getByText('Free Text'))
    await userEvent.type(screen.getByLabelText(/Describe your requirement/i), 'Test')
    await userEvent.click(screen.getByRole('button', { name: /Use This/i }))

    await waitFor(() => expect(screen.getByRole('button', { name: /Back/i })).toBeInTheDocument())
    await userEvent.click(screen.getByRole('button', { name: /Back/i }))

    expect(screen.getByText('Jira Ticket')).toBeInTheDocument()
  })
})
