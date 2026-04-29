import { render, screen, waitFor } from '@testing-library/react'
import { describe, it, expect, vi } from 'vitest'
import App from './App'

vi.mock('./api/brainClient', () => ({
  brainApi: {
    listProjects: vi.fn().mockResolvedValue([]),
    getConventions: vi.fn().mockResolvedValue([]),
    analyze: vi.fn(),
    ingest: vi.fn(),
    getProject: vi.fn(),
    getIngestionStatus: vi.fn(),
  },
}))

describe('App', () => {
  it('renders the layout structure', async () => {
    render(<App />)
    await waitFor(() => {
      expect(screen.getByText('Project Brain')).toBeInTheDocument()
      expect(screen.getAllByText('Ingest').length).toBeGreaterThan(0)
      expect(screen.getAllByText('Analyze').length).toBeGreaterThan(0)
      expect(screen.getAllByAltText('Assurant').length).toBeGreaterThan(0)
    })
  })
})
