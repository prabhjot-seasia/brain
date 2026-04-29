import Box from '@mui/material/Box'
import Card from '@mui/material/Card'
import CardContent from '@mui/material/CardContent'
import Typography from '@mui/material/Typography'
import type { ReactNode } from 'react'

export interface EmptyStateProps {
  icon?: ReactNode
  title: string
  description?: string
  action?: ReactNode
}

export default function EmptyState({ icon, title, description, action }: EmptyStateProps) {
  return (
    <Card>
      <CardContent sx={{ textAlign: 'center', py: 6, px: 3, color: 'text.secondary' }}>
        {icon && <Box sx={{ mb: 1, fontSize: 36 }}>{icon}</Box>}
        <Typography variant="subtitle1" sx={{ fontWeight: 500, mb: description ? 0.5 : 0 }}>
          {title}
        </Typography>
        {description && (
          <Typography variant="body2" color="text.secondary" sx={{ mb: action ? 2 : 0 }}>
            {description}
          </Typography>
        )}
        {action && <Box sx={{ mt: 2 }}>{action}</Box>}
      </CardContent>
    </Card>
  )
}
