import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { MemoryRouter } from 'react-router-dom'
import ProjectsPage from './ProjectsPage'

const mockListProjects = vi.fn()
const mockNavigate = vi.fn()

vi.mock('../../api/brainClient', () => ({
  brainApi: {
    listProjects: (...args: unknown[]) => mockListProjects(...args),
  },
}))

vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual('react-router-dom')
  return {
    ...actual,
    useNavigate: () => mockNavigate,
  }
})

function renderPage() {
  return render(
    <MemoryRouter>
      <ProjectsPage />
    </MemoryRouter>
  )
}

describe('ProjectsPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('shows loading state initially', () => {
    mockListProjects.mockReturnValue(new Promise(() => {}))
    renderPage()
    expect(screen.getByRole('progressbar')).toBeInTheDocument()
  })

  it('shows empty state when no projects', async () => {
    mockListProjects.mockResolvedValue([])
    renderPage()
    await waitFor(() => {
      expect(screen.getByText(/No projects indexed yet/i)).toBeInTheDocument()
    })
  })

  it('renders project data', async () => {
    mockListProjects.mockResolvedValue([
      { id: 'ce-imei', name: 'ce-IMEI', language: 'java', framework: 'spring-boot', buildTool: 'gradle', lastIngested: '2026-04-10T10:00:00Z' },
    ])
    renderPage()
    await waitFor(() => {
      expect(screen.getAllByText('ce-imei').length).toBeGreaterThan(0)
      expect(screen.getAllByText('ce-IMEI').length).toBeGreaterThan(0)
    })
  })

  it('shows error when API fails', async () => {
    mockListProjects.mockRejectedValue(new Error('fail'))
    renderPage()
    await waitFor(() => {
      expect(screen.getByText(/Failed to load projects/i)).toBeInTheDocument()
    })
  })

  it('renders Ingest Project button', async () => {
    mockListProjects.mockResolvedValue([])
    renderPage()
    await waitFor(() => {
      expect(screen.getByRole('button', { name: /Ingest Project/i })).toBeInTheDocument()
    })
  })

  it('renders language chip for each project', async () => {
    mockListProjects.mockResolvedValue([
      { id: 'p1', name: 'P1', language: 'java', framework: 'spring-boot', buildTool: 'gradle', lastIngested: '2026-04-10' },
    ])
    renderPage()
    await waitFor(() => {
      expect(screen.getAllByText('java').length).toBeGreaterThan(0)
    })
  })

  it('navigates to analyze page when project is clicked', async () => {
    mockListProjects.mockResolvedValue([
      { id: 'ce-imei', name: 'ce-IMEI', language: 'java', framework: 'spring-boot', lastIngested: null },
    ])
    renderPage()

    await waitFor(() => expect(screen.getAllByText('ce-imei').length).toBeGreaterThan(0))
    await userEvent.click(screen.getAllByText('ce-imei')[0])
    expect(mockNavigate).toHaveBeenCalledWith('/analyze?projectId=ce-imei')
  })

  it('navigates to ingest page when Ingest Project button is clicked', async () => {
    mockListProjects.mockResolvedValue([])
    renderPage()

    await waitFor(() => expect(screen.getByRole('button', { name: /Ingest Project/i })).toBeInTheDocument())
    await userEvent.click(screen.getByRole('button', { name: /Ingest Project/i }))
    expect(mockNavigate).toHaveBeenCalledWith('/ingest')
  })

  it('shows "Never indexed" for projects without lastIngested', async () => {
    mockListProjects.mockResolvedValue([
      { id: 'new', name: 'New', language: 'java', framework: null, lastIngested: null },
    ])
    renderPage()
    await waitFor(() => {
      expect(screen.getAllByText(/Never/i).length).toBeGreaterThan(0)
    })
  })
})
