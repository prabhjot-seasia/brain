import Chip from '@mui/material/Chip'
import type { ChipProps } from '@mui/material/Chip'
import { statusColor } from './statusRegistry'

export interface StatusChipProps {
  status: string | null | undefined
  label?: string
  size?: ChipProps['size']
  variant?: ChipProps['variant']
}

export default function StatusChip({ status, label, size = 'small', variant = 'filled' }: StatusChipProps) {
  const display = label ?? (status ? status : '—')
  return <Chip size={size} variant={variant} color={statusColor(status)} label={display} />
}
