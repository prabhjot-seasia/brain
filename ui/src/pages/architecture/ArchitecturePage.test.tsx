import { render, screen, waitFor } from '@testing-library/react'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { MemoryRouter } from 'react-router-dom'
import ArchitecturePage from './ArchitecturePage'

const mockListProjects = vi.fn()
const mockGetArchitecture = vi.fn()

vi.mock('../../api/brainClient', () => ({
  brainApi: {
    listProjects: (...args: unknown[]) => mockListProjects(...args),
    getArchitecture: (...args: unknown[]) => mockGetArchitecture(...args),
  },
}))

vi.mock('mermaid', () => ({
  default: {
    initialize: vi.fn(),
    render: vi.fn().mockResolvedValue({ svg: '<svg data-testid="rendered-svg"></svg>' }),
  },
}))

function renderPage() {
  return render(
    <MemoryRouter>
      <ArchitecturePage />
    </MemoryRouter>
  )
}

describe('ArchitecturePage', () => {
  beforeEach(() => { vi.clearAllMocks() })

  it('renders called services, queues, and endpoints from the architecture view', async () => {
    mockListProjects.mockResolvedValue([
      { id: 'ce-app', name: 'ce-APP', language: 'java', framework: 'spring-boot', buildTool: 'gradle', lastIngested: null, description: '' },
    ])
    mockGetArchitecture.mockResolvedValue({
      projectId: 'ce-app',
      projectName: 'ce-APP',
      kind: 'APPLICATION',
      calledServices: [
        { id: 's1', name: 'promoter', baseUrlTemplate: 'http://promoter', inferredProjectId: null, source: 'YAML' },
      ],
      publishesTo: [
        { id: 'q1', queueType: 'SQS', name: 'shipment-tracking', arn: null, source: 'ANNOTATION' },
      ],
      consumesFrom: [
        { id: 'q2', queueType: 'KAFKA', name: 'ordergraph-aeh', arn: null, source: 'ANNOTATION' },
      ],
      exposedEndpoints: [
        { id: 'e1', path: '/api/v1/orders', httpMethod: 'GET', source: 'AST' },
      ],
      runtimeEdges: [],
      counts: { calledServices: 1, publishesTo: 1, consumesFrom: 1, endpoints: 1, runtimeEdges: 0 },
    })

    renderPage()

    await waitFor(() => {
      // Desktop table + mobile card both render in jsdom
      expect(screen.getAllByText('promoter').length).toBeGreaterThanOrEqual(1)
      expect(screen.getAllByText('shipment-tracking').length).toBeGreaterThanOrEqual(1)
      expect(screen.getAllByText('ordergraph-aeh').length).toBeGreaterThanOrEqual(1)
      expect(screen.getAllByText('/api/v1/orders').length).toBeGreaterThanOrEqual(1)
      expect(screen.getByText(/kind: APPLICATION/)).toBeInTheDocument()
      expect(screen.getAllByRole('table').length).toBeGreaterThanOrEqual(1)
    })
  })

  it('shows the empty-state alert when no edges exist', async () => {
    mockListProjects.mockResolvedValue([
      { id: 'small', name: 'small', language: 'java', framework: 'spring-boot', buildTool: 'gradle', lastIngested: null, description: '' },
    ])
    mockGetArchitecture.mockResolvedValue({
      projectId: 'small', projectName: 'small', kind: null,
      calledServices: [], publishesTo: [], consumesFrom: [], exposedEndpoints: [],
      runtimeEdges: [],
      counts: { calledServices: 0, publishesTo: 0, consumesFrom: 0, endpoints: 0, runtimeEdges: 0 },
    })

    renderPage()

    await waitFor(() => {
      expect(screen.getByText(/No service \/ queue \/ endpoint edges found/i)).toBeInTheDocument()
    })
  })

  it('surfaces an error when getArchitecture rejects', async () => {
    mockListProjects.mockResolvedValue([
      { id: 'ce-app', name: 'ce-APP', language: 'java', framework: 'spring-boot', buildTool: 'gradle', lastIngested: null, description: '' },
    ])
    mockGetArchitecture.mockRejectedValue(new Error('boom'))

    renderPage()

    await waitFor(() => {
      expect(screen.getByText(/Failed to load architecture for ce-app/i)).toBeInTheDocument()
    })
  })
})
