import Alert from '@mui/material/Alert'
import AlertTitle from '@mui/material/AlertTitle'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import CircularProgress from '@mui/material/CircularProgress'
import type { ReactNode } from 'react'
import EmptyState from './EmptyState'

export interface DataStateProps {
  loading?: boolean
  error?: string | null
  isEmpty?: boolean
  emptyTitle?: string
  emptyDescription?: string
  emptyIcon?: ReactNode
  emptyAction?: ReactNode
  onRetry?: () => void
  loadingMessage?: string
  children: ReactNode
}

export default function DataState({
  loading = false,
  error = null,
  isEmpty = false,
  emptyTitle = 'No data yet.',
  emptyDescription,
  emptyIcon,
  emptyAction,
  onRetry,
  loadingMessage,
  children,
}: DataStateProps) {
  if (loading) {
    return (
      <Box sx={{ display: 'flex', flexDirection: 'column', alignItems: 'center', py: 6, gap: 1.5 }}>
        <CircularProgress size={28} />
        {loadingMessage && (
          <Box sx={{ color: 'text.secondary', fontSize: 14 }}>{loadingMessage}</Box>
        )}
      </Box>
    )
  }
  if (error) {
    return (
      <Alert
        severity="error"
        sx={{ mb: 2 }}
        action={onRetry ? <Button color="inherit" size="small" onClick={onRetry}>Retry</Button> : undefined}
      >
        <AlertTitle>Something went wrong</AlertTitle>
        {error}
      </Alert>
    )
  }
  if (isEmpty) {
    return (
      <EmptyState
        icon={emptyIcon}
        title={emptyTitle}
        description={emptyDescription}
        action={emptyAction}
      />
    )
  }
  return <>{children}</>
}
