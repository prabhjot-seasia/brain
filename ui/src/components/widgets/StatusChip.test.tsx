import { render, screen } from '@testing-library/react'
import { describe, it, expect } from 'vitest'
import StatusChip from './StatusChip'

describe('StatusChip', () => {
  it('renders the label as the status when no override given', () => {
    render(<StatusChip status="OK" />)
    expect(screen.getByText('OK')).toBeInTheDocument()
  })

  it('renders an em dash for null/undefined status', () => {
    render(<StatusChip status={null} />)
    expect(screen.getByText('—')).toBeInTheDocument()
  })

  it('uses the explicit label when provided', () => {
    render(<StatusChip status="FAILED" label="3 of 5 sections failed" />)
    expect(screen.getByText('3 of 5 sections failed')).toBeInTheDocument()
  })

  it('falls back to default color for unknown status', () => {
    render(<StatusChip status="MYSTERY_STATUS" />)
    expect(screen.getByText('MYSTERY_STATUS')).toBeInTheDocument()
  })

  it('treats lowercase status the same as uppercase', () => {
    render(<StatusChip status="ok" />)
    expect(screen.getByText('ok')).toBeInTheDocument()
  })
})
