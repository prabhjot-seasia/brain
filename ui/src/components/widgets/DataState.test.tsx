import { render, screen } from '@testing-library/react'
import { describe, it, expect, vi } from 'vitest'
import userEvent from '@testing-library/user-event'
import DataState from './DataState'

describe('DataState', () => {
  it('renders progress when loading', () => {
    render(<DataState loading loadingMessage="hold on">child</DataState>)
    expect(screen.getByText(/hold on/i)).toBeInTheDocument()
  })

  it('renders error alert with retry button', async () => {
    const onRetry = vi.fn()
    render(<DataState error="boom" onRetry={onRetry}>child</DataState>)
    expect(screen.getByText('boom')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: /retry/i }))
    expect(onRetry).toHaveBeenCalled()
  })

  it('renders empty state when isEmpty', () => {
    render(<DataState isEmpty emptyTitle="Nothing here" emptyDescription="Try again later.">child</DataState>)
    expect(screen.getByText('Nothing here')).toBeInTheDocument()
    expect(screen.getByText('Try again later.')).toBeInTheDocument()
    expect(screen.queryByText('child')).toBeNull()
  })

  it('renders children when not loading / error / empty', () => {
    render(<DataState>actual content</DataState>)
    expect(screen.getByText('actual content')).toBeInTheDocument()
  })
})
