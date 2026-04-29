import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, it, expect, vi } from 'vitest'
import Header from './Header'

describe('Header', () => {
  it('renders the app title', () => {
    render(<Header onMenuToggle={vi.fn()} />)
    expect(screen.getByText('Project Brain')).toBeInTheDocument()
  })

  it('calls onMenuToggle when hamburger button is clicked', async () => {
    const toggle = vi.fn()
    render(<Header onMenuToggle={toggle} />)
    await userEvent.click(screen.getByLabelText('toggle navigation'))
    expect(toggle).toHaveBeenCalledOnce()
  })
})
