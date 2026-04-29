import Drawer from '@mui/material/Drawer'
import List from '@mui/material/List'
import ListItemButton from '@mui/material/ListItemButton'
import ListItemIcon from '@mui/material/ListItemIcon'
import ListItemText from '@mui/material/ListItemText'
import ListSubheader from '@mui/material/ListSubheader'
import Divider from '@mui/material/Divider'
import Tooltip from '@mui/material/Tooltip'
import Box from '@mui/material/Box'
import FolderOpenIcon from '@mui/icons-material/FolderOpen'
import AnalyticsIcon from '@mui/icons-material/Analytics'
import RuleIcon from '@mui/icons-material/Rule'
import CloudUploadIcon from '@mui/icons-material/CloudUpload'
import DescriptionIcon from '@mui/icons-material/Description'
import ConfirmationNumberIcon from '@mui/icons-material/ConfirmationNumber'
import MergeIcon from '@mui/icons-material/Merge'
import SchoolIcon from '@mui/icons-material/School'
import DataUsageIcon from '@mui/icons-material/DataUsage'
import ShieldIcon from '@mui/icons-material/Shield'
import AutoAwesomeIcon from '@mui/icons-material/AutoAwesome'
import AccountTreeIcon from '@mui/icons-material/AccountTree'
import VisibilityIcon from '@mui/icons-material/Visibility'
import LibraryAddCheckIcon from '@mui/icons-material/LibraryAddCheck'
import TuneIcon from '@mui/icons-material/Tune'
import { useNavigate, useLocation } from 'react-router-dom'
import { ROUTES } from '../../routes'

const DRAWER_WIDTH = 200
const TOOLBAR_HEIGHT = 64

const NAV_GROUPS = [
  {
    group: 'Workspace',
    items: [
      { label: 'Projects',     icon: <FolderOpenIcon />,   path: ROUTES.PROJECTS },
      { label: 'Ingest',       icon: <CloudUploadIcon />,  path: ROUTES.INGEST },
      { label: 'Architecture', icon: <AccountTreeIcon />,  path: ROUTES.ARCHITECTURE },
    ],
  },
  {
    group: 'Develop',
    items: [
      { label: 'Analyze',       icon: <AnalyticsIcon />,   path: ROUTES.ANALYZE },
      { label: 'Autodev',       icon: <AutoAwesomeIcon />, path: ROUTES.AUTODEV },
      { label: 'Pull Requests', icon: <MergeIcon />,       path: ROUTES.PRS },
    ],
  },
  {
    group: 'Deliver',
    items: [
      { label: 'Docs',    icon: <DescriptionIcon />,        path: ROUTES.DOCS },
      { label: 'Tickets', icon: <ConfirmationNumberIcon />, path: ROUTES.TICKETS },
    ],
  },
  {
    group: 'Knowledge',
    items: [
      { label: 'Conventions', icon: <RuleIcon />,            path: ROUTES.CONVENTIONS },
      { label: 'Learning',    icon: <SchoolIcon />,          path: ROUTES.LEARNING },
      { label: 'Rule Packs',  icon: <LibraryAddCheckIcon />, path: ROUTES.RULE_PACKS },
    ],
  },
  {
    group: 'Operations',
    items: [
      { label: 'Avengers',      icon: <ShieldIcon />,     path: ROUTES.AVENGERS },
      { label: 'HAWKEYE',       icon: <VisibilityIcon />, path: ROUTES.SECURITY },
      { label: 'ORACLE Budget', icon: <TuneIcon />,       path: ROUTES.ORACLE },
      { label: 'Token Usage',   icon: <DataUsageIcon />,  path: ROUTES.TOKENS },
    ],
  },
]

interface SidebarProps {
  mobileOpen: boolean
  onClose: () => void
}

export default function Sidebar({ mobileOpen, onClose }: SidebarProps) {
  const navigate = useNavigate()
  const { pathname } = useLocation()

  const handleNav = (path: string) => {
    navigate(path)
    onClose()
  }

  const drawerContent = (
    <Box role="navigation" aria-label="primary" sx={{ pt: 1, pb: 2 }}>
      {NAV_GROUPS.map(({ group, items }, idx) => (
        <Box key={group}>
          {idx > 0 && <Divider sx={{ my: 1, mx: 2 }} />}
          <List
            disablePadding
            subheader={
              <ListSubheader
                disableSticky
                component="div"
                sx={{
                  bgcolor: 'transparent',
                  color: 'text.secondary',
                  fontSize: '0.7rem',
                  fontWeight: 700,
                  letterSpacing: '0.08em',
                  textTransform: 'uppercase',
                  lineHeight: 2,
                  px: 2,
                }}
              >
                {group}
              </ListSubheader>
            }
          >
            {items.map(({ label, icon, path }) => {
              const active = pathname === path || pathname.startsWith(path + '/')
              return (
                <Tooltip title={label} placement="right" key={path}>
                  <ListItemButton
                    onClick={() => handleNav(path)}
                    selected={active}
                    sx={{
                      mx: 1,
                      mb: 0.25,
                      borderRadius: 1,
                      '&.Mui-selected': {
                        backgroundColor: 'action.selected',
                        '& .MuiListItemIcon-root': { color: 'primary.main' },
                        '& .MuiListItemText-primary': { color: 'primary.main', fontWeight: 600 },
                      },
                      '&:hover': { backgroundColor: 'action.hover' },
                    }}
                  >
                    <ListItemIcon sx={{ minWidth: 36, color: active ? 'primary.main' : 'text.secondary' }}>
                      {icon}
                    </ListItemIcon>
                    <ListItemText
                      primary={label}
                      primaryTypographyProps={{ variant: 'body2' }}
                    />
                  </ListItemButton>
                </Tooltip>
              )
            })}
          </List>
        </Box>
      ))}
    </Box>
  )

  return (
    <>
      <Drawer
        variant="temporary"
        open={mobileOpen}
        onClose={onClose}
        ModalProps={{ keepMounted: true }}
        sx={{
          display: { xs: 'block', md: 'none' },
          '& .MuiDrawer-paper': {
            width: DRAWER_WIDTH,
            boxSizing: 'border-box',
            top: TOOLBAR_HEIGHT,
            height: `calc(100% - ${TOOLBAR_HEIGHT}px)`,
            borderRight: 1,
            borderColor: 'divider',
            backgroundColor: 'background.paper',
          },
        }}
      >
        {drawerContent}
      </Drawer>

      <Drawer
        variant="permanent"
        sx={{
          display: { xs: 'none', md: 'block' },
          width: DRAWER_WIDTH,
          flexShrink: 0,
          '& .MuiDrawer-paper': {
            width: DRAWER_WIDTH,
            boxSizing: 'border-box',
            top: TOOLBAR_HEIGHT,
            height: `calc(100% - ${TOOLBAR_HEIGHT}px)`,
            borderRight: 1,
            borderColor: 'divider',
            backgroundColor: 'background.paper',
          },
        }}
      >
        {drawerContent}
      </Drawer>
    </>
  )
}

export { DRAWER_WIDTH, TOOLBAR_HEIGHT }
