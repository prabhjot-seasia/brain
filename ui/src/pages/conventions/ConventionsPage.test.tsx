import { render, screen, waitFor } from '@testing-library/react'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import ConventionsPage from './ConventionsPage'

const mockListProjects = vi.fn()
const mockGetConventions = vi.fn()

vi.mock('../../api/brainClient', () => ({
  brainApi: {
    listProjects: (...args: unknown[]) => mockListProjects(...args),
    getConventions: (...args: unknown[]) => mockGetConventions(...args),
  },
}))

describe('ConventionsPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockListProjects.mockResolvedValue([
      { id: 'ce-imei', name: 'ce-IMEI' },
    ])
  })

  it('renders the page heading', async () => {
    mockGetConventions.mockResolvedValue([])
    render(<ConventionsPage />)
    await waitFor(() => {
      expect(screen.getByRole('heading', { level: 5, name: /Conventions/i })).toBeInTheDocument()
    })
  })

  it('shows empty state when no conventions exist', async () => {
    mockGetConventions.mockResolvedValue([])
    render(<ConventionsPage />)
    await waitFor(() => {
      expect(screen.getByText(/No conventions found/i)).toBeInTheDocument()
    })
  })

  it('renders conventions data', async () => {
    mockGetConventions.mockResolvedValue([
      { id: 1, rule: 'Use Lombok @RequiredArgsConstructor', category: 'coding-style', sourceFile: 'CONTRIBUTING.md', trustWeight: 1.5, projectId: 'ce-imei' },
      { id: 2, rule: 'Log4j2 not Logback', category: 'logging', sourceFile: 'inferred from 12 occurrences', trustWeight: 1.0, projectId: 'ce-imei' },
    ])
    render(<ConventionsPage />)

    await waitFor(() => {
      expect(screen.getAllByText('Use Lombok @RequiredArgsConstructor').length).toBeGreaterThan(0)
      expect(screen.getAllByText('Log4j2 not Logback').length).toBeGreaterThan(0)
    })
  })

  it('shows error alert when conventions fail to load', async () => {
    mockGetConventions.mockRejectedValue(new Error('Network error'))
    render(<ConventionsPage />)

    await waitFor(() => {
      expect(screen.getByText(/Failed to load conventions/i)).toBeInTheDocument()
    })
  })

  it('renders category filter dropdown', async () => {
    mockGetConventions.mockResolvedValue([])
    render(<ConventionsPage />)
    await waitFor(() => {
      expect(screen.getByLabelText(/Category/i)).toBeInTheDocument()
    })
  })

  it('renders project selector', async () => {
    mockGetConventions.mockResolvedValue([])
    render(<ConventionsPage />)
    await waitFor(() => {
      expect(screen.getByLabelText(/Project/i)).toBeInTheDocument()
    })
  })
})
