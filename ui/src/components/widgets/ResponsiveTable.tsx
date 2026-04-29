import Box from '@mui/material/Box'
import Card from '@mui/material/Card'
import CardActionArea from '@mui/material/CardActionArea'
import CardContent from '@mui/material/CardContent'
import Stack from '@mui/material/Stack'
import Table from '@mui/material/Table'
import TableBody from '@mui/material/TableBody'
import TableCell from '@mui/material/TableCell'
import TableContainer from '@mui/material/TableContainer'
import TableHead from '@mui/material/TableHead'
import TableRow from '@mui/material/TableRow'
import Typography from '@mui/material/Typography'
import type { ReactNode } from 'react'
import KeyValueCard from './KeyValueCard'

export interface ResponsiveTableColumn<R> {
  key: string
  label: string
  render: (row: R) => ReactNode
  align?: 'left' | 'right' | 'center'
  primary?: boolean
  wordBreak?: boolean
}

export interface ResponsiveTableProps<R> {
  rows: R[]
  columns: ResponsiveTableColumn<R>[]
  rowKey: (row: R) => string
  onRowClick?: (row: R) => void
  size?: 'small' | 'medium'
  ariaLabel?: string
}

export default function ResponsiveTable<R>({
  rows,
  columns,
  rowKey,
  onRowClick,
  size = 'small',
  ariaLabel,
}: ResponsiveTableProps<R>) {
  const primaryCols = columns.filter(c => c.primary)
  const detailCols = columns.filter(c => !c.primary)

  return (
    <>
      <Box sx={{ display: { xs: 'none', md: 'block' } }}>
        <TableContainer component={Card}>
          <Table size={size} aria-label={ariaLabel}>
            <TableHead>
              <TableRow sx={{ backgroundColor: 'background.default' }}>
                {columns.map(c => (
                  <TableCell
                    key={c.key}
                    align={c.align ?? 'left'}
                    sx={{ fontWeight: 600, color: 'primary.main' }}
                  >
                    {c.label}
                  </TableCell>
                ))}
              </TableRow>
            </TableHead>
            <TableBody>
              {rows.map(row => (
                <TableRow
                  key={rowKey(row)}
                  hover={!!onRowClick}
                  onClick={onRowClick ? () => onRowClick(row) : undefined}
                  sx={onRowClick ? { cursor: 'pointer' } : undefined}
                >
                  {columns.map(c => (
                    <TableCell
                      key={c.key}
                      align={c.align ?? 'left'}
                      sx={c.wordBreak ? { wordBreak: 'break-all' } : undefined}
                    >
                      {c.render(row)}
                    </TableCell>
                  ))}
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </TableContainer>
      </Box>

      <Stack spacing={1.5} sx={{ display: { xs: 'flex', md: 'none' } }}>
        {rows.map(row => (
          <MobileCard
            key={rowKey(row)}
            row={row}
            primaryCols={primaryCols.length > 0 ? primaryCols : columns.slice(0, 1)}
            detailCols={primaryCols.length > 0 ? detailCols : columns.slice(1)}
            onClick={onRowClick}
          />
        ))}
      </Stack>
    </>
  )
}

interface MobileCardProps<R> {
  row: R
  primaryCols: ResponsiveTableColumn<R>[]
  detailCols: ResponsiveTableColumn<R>[]
  onClick?: (row: R) => void
}

function MobileCard<R>({ row, primaryCols, detailCols, onClick }: MobileCardProps<R>) {
  const body = (
    <CardContent sx={{ p: 2 }}>
      <Stack spacing={0.5} sx={{ mb: detailCols.length > 0 ? 1 : 0 }}>
        {primaryCols.map(c => (
          <Box key={c.key} sx={c.wordBreak ? { wordBreak: 'break-all' } : undefined}>
            <Typography
              variant="subtitle2"
              component="div"
              sx={{ fontWeight: 600 }}
            >
              {c.render(row)}
            </Typography>
          </Box>
        ))}
      </Stack>
      {detailCols.length > 0 && (
        <KeyValueCard
          rows={detailCols.map(c => ({ label: c.label, value: c.render(row) }))}
          columns={detailCols.length > 4 ? 1 : 2}
        />
      )}
    </CardContent>
  )
  if (onClick) {
    return (
      <Card>
        <CardActionArea onClick={() => onClick(row)}>{body}</CardActionArea>
      </Card>
    )
  }
  return <Card>{body}</Card>
}
