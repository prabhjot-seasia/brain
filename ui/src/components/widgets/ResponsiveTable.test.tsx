import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, it, expect, vi } from 'vitest'
import ResponsiveTable, { type ResponsiveTableColumn } from './ResponsiveTable'

interface Row { id: string; name: string; status: string; count: number }

const ROWS: Row[] = [
  { id: 'a', name: 'Alpha', status: 'OK', count: 12 },
  { id: 'b', name: 'Bravo', status: 'FAILED', count: 7 },
]

const COLUMNS: ResponsiveTableColumn<Row>[] = [
  { key: 'name', label: 'Name', render: r => r.name, primary: true },
  { key: 'status', label: 'Status', render: r => r.status },
  { key: 'count', label: 'Count', render: r => r.count, align: 'right' },
]

describe('ResponsiveTable', () => {
  it('renders desktop table with headers and rows', () => {
    render(<ResponsiveTable rows={ROWS} columns={COLUMNS} rowKey={r => r.id} />)
    expect(screen.getByRole('table')).toBeInTheDocument()
    expect(screen.getAllByText('Alpha').length).toBeGreaterThanOrEqual(1)
    expect(screen.getAllByText('Bravo').length).toBeGreaterThanOrEqual(1)
  })

  it('renders mobile card stack alongside the desktop table (jsdom does not apply CSS media queries)', () => {
    render(<ResponsiveTable rows={ROWS} columns={COLUMNS} rowKey={r => r.id} />)
    expect(screen.getAllByText('OK').length).toBeGreaterThanOrEqual(1)
    expect(screen.getAllByText('FAILED').length).toBeGreaterThanOrEqual(1)
  })

  it('fires onRowClick on row activation', async () => {
    const onClick = vi.fn()
    render(<ResponsiveTable rows={ROWS} columns={COLUMNS} rowKey={r => r.id} onRowClick={onClick} />)
    const cells = screen.getAllByText('Alpha')
    await userEvent.click(cells[0])
    expect(onClick).toHaveBeenCalledWith(ROWS[0])
  })

  it('handles empty rows gracefully without throwing', () => {
    render(<ResponsiveTable rows={[]} columns={COLUMNS} rowKey={r => r.id} />)
    expect(screen.getByRole('table')).toBeInTheDocument()
  })
})
