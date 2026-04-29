import { render, screen, waitFor } from '@testing-library/react'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { MemoryRouter } from 'react-router-dom'
import HawkeyeAsffPage from './HawkeyeAsffPage'

const mockListProjects = vi.fn()
const mockGetAsff = vi.fn()

vi.mock('../../api/brainClient', () => ({
  brainApi: {
    listProjects: (...args: unknown[]) => mockListProjects(...args),
    getHawkeyeAsff: (...args: unknown[]) => mockGetAsff(...args),
  },
}))

function renderPage() {
  return render(<MemoryRouter><HawkeyeAsffPage /></MemoryRouter>)
}

describe('HawkeyeAsffPage', () => {
  beforeEach(() => { vi.clearAllMocks() })

  it('renders findings table with severity chips', async () => {
    mockListProjects.mockResolvedValue([
      { id: 'ce-app', name: 'ce-APP', language: 'java', framework: 'spring-boot', buildTool: 'gradle', lastIngested: null, description: '' },
    ])
    mockGetAsff.mockResolvedValue({
      Findings: [
        {
          SchemaVersion: '2018-10-08', Id: 'brain/hawkeye/x',
          Title: 'HAWKEYE security review: BLOCKED',
          Severity: { Label: 'CRITICAL', Normalized: 90 },
          Workflow: { Status: 'NEW' },
          Compliance: { Status: 'FAILED' },
          CreatedAt: '2026-04-27T00:00:00Z',
        },
      ],
    })

    renderPage()

    await waitFor(() => {
      // Desktop table + mobile card both render in jsdom
      expect(screen.getAllByText(/HAWKEYE security review: BLOCKED/).length).toBeGreaterThanOrEqual(1)
      expect(screen.getAllByText(/CRITICAL/).length).toBeGreaterThan(0)
      expect(screen.getAllByText(/FAILED/).length).toBeGreaterThanOrEqual(1)
      expect(screen.getByRole('table')).toBeInTheDocument()
    })
  })

  it('shows empty-state copy when no findings exist', async () => {
    mockListProjects.mockResolvedValue([
      { id: 'p', name: 'p', language: 'java', framework: 'spring-boot', buildTool: 'gradle', lastIngested: null, description: '' },
    ])
    mockGetAsff.mockResolvedValue({ Findings: [] })

    renderPage()
    await waitFor(() => {
      expect(screen.getByText(/No HAWKEYE findings yet/i)).toBeInTheDocument()
    })
  })
})
