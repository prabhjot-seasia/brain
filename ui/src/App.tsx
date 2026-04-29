import { useState } from 'react'
import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom'
import Box from '@mui/material/Box'
import Header from './components/layout/Header'
import Sidebar, { TOOLBAR_HEIGHT } from './components/layout/Sidebar'
import ProjectsPage from './pages/projects/ProjectsPage'
import IngestPage from './pages/projects/IngestPage'
import RequirementIntakePage from './pages/intake/RequirementIntakePage'
import FullDocsPage from './pages/docs/FullDocsPage'
import TicketProposalPage from './pages/tickets/TicketProposalPage'
import ConventionsPage from './pages/conventions/ConventionsPage'
import PrStatusPage from './pages/prs/PrStatusPage'
import LearningDashboardPage from './pages/learning/LearningDashboardPage'
import TokenUsagePage from './pages/monitor/TokenUsagePage'
import AvengerReviewPage from './pages/avengers/AvengerReviewPage'
import AutonomousDevPage from './pages/autodev/AutonomousDevPage'
import ArchitecturePage from './pages/architecture/ArchitecturePage'
import HawkeyeAsffPage from './pages/security/HawkeyeAsffPage'
import RulePacksPage from './pages/rulepacks/RulePacksPage'
import OracleBudgetPage from './pages/oracle/OracleBudgetPage'
import { ROUTES } from './routes'
import { JobToastWatcher } from './components/widgets'

export default function App() {
  const [mobileOpen, setMobileOpen] = useState(false)

  return (
    <BrowserRouter basename={import.meta.env.BASE_URL}>
      <JobToastWatcher />
      <Box sx={{ display: 'flex', flexDirection: 'column', minHeight: '100vh' }}>
        <Header onMenuToggle={() => setMobileOpen(prev => !prev)} />
        <Box sx={{ display: 'flex', flexGrow: 1 }}>
          <Sidebar mobileOpen={mobileOpen} onClose={() => setMobileOpen(false)} />
          <Box
            component="main"
            sx={{
              flexGrow: 1,
              mt: `${TOOLBAR_HEIGHT}px`,
              p: { xs: 2, sm: 3 },
              backgroundColor: 'background.default',
              minHeight: `calc(100vh - ${TOOLBAR_HEIGHT}px)`,
            }}
          >
            <Box sx={{ width: '100%' }}>
              <Routes>
                <Route path={ROUTES.ROOT} element={<Navigate to={ROUTES.PROJECTS} replace />} />
                <Route path={ROUTES.PROJECTS} element={<ProjectsPage />} />
                <Route path={ROUTES.INGEST} element={<IngestPage />} />
                <Route path={ROUTES.ANALYZE} element={<RequirementIntakePage />} />
                <Route path={ROUTES.AUTODEV} element={<AutonomousDevPage />} />
                <Route path={ROUTES.DOCS} element={<FullDocsPage />} />
                <Route path={ROUTES.TICKETS} element={<TicketProposalPage />} />
                <Route path={ROUTES.PRS} element={<PrStatusPage />} />
                <Route path={ROUTES.LEARNING} element={<LearningDashboardPage />} />
                <Route path={ROUTES.TOKENS} element={<TokenUsagePage />} />
                <Route path={ROUTES.AVENGERS} element={<AvengerReviewPage />} />
                <Route path={ROUTES.CONVENTIONS} element={<ConventionsPage />} />
                <Route path={ROUTES.ARCHITECTURE} element={<ArchitecturePage />} />
                <Route path={ROUTES.SECURITY} element={<HawkeyeAsffPage />} />
                <Route path={ROUTES.RULE_PACKS} element={<RulePacksPage />} />
                <Route path={ROUTES.ORACLE} element={<OracleBudgetPage />} />
              </Routes>
            </Box>
          </Box>
        </Box>
      </Box>
    </BrowserRouter>
  )
}
