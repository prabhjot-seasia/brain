import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, it, expect, vi } from 'vitest'
import { MemoryRouter } from 'react-router-dom'
import Sidebar from './Sidebar'

const mockNavigate = vi.fn()

vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual('react-router-dom')
  return {
    ...actual,
    useNavigate: () => mockNavigate,
  }
})

describe('Sidebar', () => {
  it('renders all navigation items', () => {
    render(
      <MemoryRouter>
        <Sidebar mobileOpen={false} onClose={vi.fn()} />
      </MemoryRouter>
    )
    expect(screen.getAllByText('Projects').length).toBeGreaterThan(0)
    expect(screen.getAllByText('Ingest').length).toBeGreaterThan(0)
    expect(screen.getAllByText('Analyze').length).toBeGreaterThan(0)
    expect(screen.getAllByText('Conventions').length).toBeGreaterThan(0)
  })

  it('navigates and calls onClose when a nav item is clicked', async () => {
    const onClose = vi.fn()
    render(
      <MemoryRouter>
        <Sidebar mobileOpen={false} onClose={onClose} />
      </MemoryRouter>
    )
    const projectButtons = screen.getAllByText('Projects')
    await userEvent.click(projectButtons[0])
    expect(mockNavigate).toHaveBeenCalledWith('/projects')
    expect(onClose).toHaveBeenCalled()
  })

  it('renders all five group subheaders', () => {
    render(
      <MemoryRouter>
        <Sidebar mobileOpen={false} onClose={vi.fn()} />
      </MemoryRouter>
    )
    for (const group of ['Workspace', 'Develop', 'Deliver', 'Knowledge', 'Operations']) {
      expect(screen.getAllByText(group).length).toBeGreaterThan(0)
    }
  })

  it('renders every existing route across the new groups', () => {
    render(
      <MemoryRouter>
        <Sidebar mobileOpen={false} onClose={vi.fn()} />
      </MemoryRouter>
    )
    const expected = [
      'Projects', 'Ingest', 'Architecture',
      'Analyze', 'Autodev', 'Pull Requests',
      'Docs', 'Tickets',
      'Conventions', 'Learning', 'Rule Packs',
      'Avengers', 'HAWKEYE', 'ORACLE Budget', 'Token Usage',
    ]
    for (const label of expected) {
      expect(screen.getAllByText(label).length).toBeGreaterThan(0)
    }
  })

  it('marks the sidebar as a navigation landmark', () => {
    render(
      <MemoryRouter>
        <Sidebar mobileOpen={false} onClose={vi.fn()} />
      </MemoryRouter>
    )
    const landmarks = screen.getAllByRole('navigation')
    expect(landmarks.length).toBeGreaterThan(0)
  })
})
