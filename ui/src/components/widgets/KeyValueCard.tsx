import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'
import type { ReactNode } from 'react'

export interface KeyValueRow {
  label: string
  value: ReactNode
}

export interface KeyValueCardProps {
  rows: KeyValueRow[]
  columns?: 1 | 2
  compact?: boolean
}

export default function KeyValueCard({ rows, columns = 2, compact = false }: KeyValueCardProps) {
  const grid = columns === 2 ? '1fr 1fr' : '1fr'
  return (
    <Box sx={{ display: 'grid', gridTemplateColumns: grid, gap: compact ? 0.25 : 0.5 }}>
      {rows.map((row, i) => (
        <Box key={i} sx={{ display: 'contents' }}>
          <Typography variant="caption" color="text.secondary">{row.label}</Typography>
          <Typography variant="caption" sx={{ textAlign: columns === 2 ? 'right' : 'left' }}>
            {row.value}
          </Typography>
        </Box>
      ))}
    </Box>
  )
}
