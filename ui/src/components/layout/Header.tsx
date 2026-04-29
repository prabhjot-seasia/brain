import AppBar from '@mui/material/AppBar'
import Toolbar from '@mui/material/Toolbar'
import Typography from '@mui/material/Typography'
import Box from '@mui/material/Box'
import IconButton from '@mui/material/IconButton'
import MenuIcon from '@mui/icons-material/Menu'
import HubIcon from '@mui/icons-material/Hub'

interface HeaderProps {
  onMenuToggle: () => void
}

export default function Header({ onMenuToggle }: HeaderProps) {
  return (
    <AppBar position="fixed" elevation={0}>
      <Toolbar sx={{ minHeight: 64 }}>
        <IconButton
          edge="start"
          onClick={onMenuToggle}
          sx={{ mr: 1, display: { md: 'none' }, color: 'text.primary' }}
          aria-label="toggle navigation"
        >
          <MenuIcon />
        </IconButton>

        <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, flexGrow: 1 }}>
          <HubIcon sx={{ color: 'primary.main', fontSize: { xs: 24, sm: 28 } }} />
          <Typography variant="h6" sx={{ lineHeight: 1.2, color: 'primary.main', fontWeight: 700, fontSize: { xs: 16, sm: 20 } }}>
            Project Brain
          </Typography>
        </Box>

        <Box
          component="img"
          src="/assurant-logo.svg"
          alt="Assurant"
          sx={{ height: { xs: 18, sm: 22 }, opacity: 0.7 }}
        />
      </Toolbar>
    </AppBar>
  )
}
