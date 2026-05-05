import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import TicketProposalPage from './TicketProposalPage'

const mockProposeTickets = vi.fn()
const mockCreateTickets = vi.fn()
const mockGetJiraConfig = vi.fn()
const mockGetProposalStatus = vi.fn()
const mockUseJobStream = vi.fn()
const mockStartWatching = vi.fn()

vi.mock('../../api/brainClient', () => ({
  brainApi: {
    proposeTickets: (...args: unknown[]) => mockProposeTickets(...args),
    createTickets: (...args: unknown[]) => mockCreateTickets(...args),
    getProposalStatus: (...args: unknown[]) => mockGetProposalStatus(...args),
    getJiraConfig: (...args: unknown[]) => mockGetJiraConfig(...args),
  },
}))

vi.mock('../../hooks/useJobStream', () => ({
  useJobStream: (jobId: string | null, opts: { onTerminal?: (s: unknown) => void } = {}) =>
    mockUseJobStream(jobId, opts),
}))

vi.mock('../../components/widgets', () => ({
  startWatchingJob: (...args: unknown[]) => mockStartWatching(...args),
}))

const proposeStartResponse = {
  proposalId: 'prop-1', projectKey: 'PAY', status: 'PROPOSED',
  jobId: 'job-propose-1', streamUrl: '/api/v1/jobs/stream/job-propose-1',
}

const createStartResponse = {
  projectKey: 'PAY', ticketCount: 1, jobId: 'job-create-1',
  streamUrl: '/api/v1/jobs/stream/job-create-1', attachedToExisting: false,
}

const sampleTicket = {
  title: 'Add retry logic', description: 'Implement retry',
  acceptanceCriteria: '- Max 3 retries', issueType: 'Story',
  storyPoints: 3, priority: 'High',
}

describe('TicketProposalPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockGetJiraConfig.mockResolvedValue({ configured: true, baseUrl: 'https://test.atlassian.net' })
    mockUseJobStream.mockReturnValue({ snapshot: null, status: 'idle' })
  })

  it('renders the page heading and input form', () => {
    render(<TicketProposalPage />)
    expect(screen.getByText('Create Jira Tickets')).toBeInTheDocument()
    expect(screen.getByLabelText(/Document content/i)).toBeInTheDocument()
    expect(screen.getByLabelText(/Jira Project Key/i)).toBeInTheDocument()
  })

  it('shows disabled button when fields are empty', () => {
    render(<TicketProposalPage />)
    expect(screen.getByRole('button', { name: /Propose Tickets/i })).toBeDisabled()
  })

  it('proposes tickets via job stream and shows editable cards on terminal', async () => {
    mockProposeTickets.mockResolvedValue(proposeStartResponse)
    mockGetProposalStatus.mockResolvedValue({
      proposalId: 'prop-1', projectKey: 'PAY', status: 'PROPOSED',
      tickets: [sampleTicket, { ...sampleTicket, title: 'Add retry tests' }],
      count: 2,
    })

    const handlers: Record<string, (s: unknown) => void> = {}
    mockUseJobStream.mockImplementation((jobId, opts) => {
      if (jobId && opts.onTerminal) handlers[jobId] = opts.onTerminal
      return { snapshot: null, status: 'idle' }
    })

    render(<TicketProposalPage />)
    await userEvent.type(screen.getByLabelText(/Document content/i), 'Add payment retry')
    await userEvent.type(screen.getByLabelText(/Jira Project Key/i), 'PAY')
    await userEvent.click(screen.getByRole('button', { name: /Propose Tickets/i }))

    await waitFor(() => {
      expect(mockProposeTickets).toHaveBeenCalled()
      expect(mockStartWatching).toHaveBeenCalledWith('job-propose-1', expect.stringContaining('PAY'))
    })

    await handlers["job-propose-1"]({ status: 'SUCCEEDED' })

    await waitFor(() => {
      expect(screen.getByText('2 Proposed Tickets')).toBeInTheDocument()
      expect(screen.getByDisplayValue('Add retry logic')).toBeInTheDocument()
    })
  })

  it('creates tickets via job stream and shows success with Jira keys', async () => {
    mockProposeTickets.mockResolvedValue(proposeStartResponse)
    mockCreateTickets.mockResolvedValue(createStartResponse)
    mockGetProposalStatus.mockResolvedValue({
      proposalId: 'prop-1', projectKey: 'PAY', status: 'PROPOSED',
      tickets: [sampleTicket], count: 1,
    })

    const handlers: Record<string, (s: unknown) => void> = {}
    mockUseJobStream.mockImplementation((jobId, opts) => {
      if (jobId && opts.onTerminal) handlers[jobId] = opts.onTerminal
      return { snapshot: null, status: 'idle' }
    })

    render(<TicketProposalPage />)
    await userEvent.type(screen.getByLabelText(/Document content/i), 'Req')
    await userEvent.type(screen.getByLabelText(/Jira Project Key/i), 'PAY')
    await userEvent.click(screen.getByRole('button', { name: /Propose Tickets/i }))

    await waitFor(() => expect(mockUseJobStream).toHaveBeenCalledWith('job-propose-1', expect.anything()))
    await handlers["job-propose-1"]({ status: 'SUCCEEDED' })

    await waitFor(() => expect(screen.getByText('1 Proposed Tickets')).toBeInTheDocument())
    await userEvent.click(screen.getByRole('button', { name: /Create All in Jira/i }))

    await waitFor(() => expect(mockUseJobStream).toHaveBeenCalledWith('job-create-1', expect.anything()))
    await handlers["job-create-1"]({ status: 'SUCCEEDED', result: JSON.stringify({ createdKeys: ['PAY-101'], count: 1 }) })

    await waitFor(() => {
      expect(screen.getByText('1 Tickets Created')).toBeInTheDocument()
      expect(screen.getByText('PAY-101')).toBeInTheDocument()
    })
  })

  it('allows removing a ticket before creation', async () => {
    mockProposeTickets.mockResolvedValue(proposeStartResponse)
    mockGetProposalStatus.mockResolvedValue({
      proposalId: 'prop-1', projectKey: 'PAY', status: 'PROPOSED',
      tickets: [
        { ...sampleTicket, title: 'Keep this' },
        { ...sampleTicket, title: 'Remove this', issueType: 'Task' },
      ],
      count: 2,
    })

    const handlers: Record<string, (s: unknown) => void> = {}
    mockUseJobStream.mockImplementation((jobId, opts) => {
      if (jobId && opts.onTerminal) handlers[jobId] = opts.onTerminal
      return { snapshot: null, status: 'idle' }
    })

    render(<TicketProposalPage />)
    await userEvent.type(screen.getByLabelText(/Document content/i), 'Req')
    await userEvent.type(screen.getByLabelText(/Jira Project Key/i), 'PAY')
    await userEvent.click(screen.getByRole('button', { name: /Propose Tickets/i }))

    await waitFor(() => expect(mockUseJobStream).toHaveBeenCalledWith('job-propose-1', expect.anything()))
    await handlers["job-propose-1"]({ status: 'SUCCEEDED' })

    await waitFor(() => expect(screen.getByText('2 Proposed Tickets')).toBeInTheDocument())

    const deleteButtons = screen.getAllByLabelText(/remove ticket/i)
    await userEvent.click(deleteButtons[1])

    expect(screen.getByText('1 Proposed Tickets')).toBeInTheDocument()
    expect(screen.queryByDisplayValue('Remove this')).not.toBeInTheDocument()
  })

  it('shows Back button to return to input', async () => {
    mockProposeTickets.mockResolvedValue(proposeStartResponse)
    mockGetProposalStatus.mockResolvedValue({
      proposalId: 'prop-1', projectKey: 'PAY', status: 'PROPOSED',
      tickets: [sampleTicket], count: 1,
    })

    const handlers: Record<string, (s: unknown) => void> = {}
    mockUseJobStream.mockImplementation((jobId, opts) => {
      if (jobId && opts.onTerminal) handlers[jobId] = opts.onTerminal
      return { snapshot: null, status: 'idle' }
    })

    render(<TicketProposalPage />)
    await userEvent.type(screen.getByLabelText(/Document content/i), 'Req')
    await userEvent.type(screen.getByLabelText(/Jira Project Key/i), 'PAY')
    await userEvent.click(screen.getByRole('button', { name: /Propose Tickets/i }))

    await waitFor(() => expect(mockUseJobStream).toHaveBeenCalledWith('job-propose-1', expect.anything()))
    await handlers["job-propose-1"]({ status: 'SUCCEEDED' })

    await waitFor(() => expect(screen.getByRole('button', { name: /Back/i })).toBeInTheDocument())
    await userEvent.click(screen.getByRole('button', { name: /Back/i }))
    expect(screen.getByLabelText(/Document content/i)).toBeInTheDocument()
  })
})
