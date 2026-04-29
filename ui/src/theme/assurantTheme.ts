import { createTheme } from '@mui/material/styles'

const assurantBlue = {
  50:  '#e0eef7',
  100: '#b3d4eb',
  200: '#80b7dd',
  300: '#4d9acf',
  400: '#2684c5',
  500: '#006ebb',
  600: '#0066b5',
  700: '#005bac',
  800: '#0051a4',
  900: '#003f96',
  A100: '#c1d6ff',
  A200: '#8eb5ff',
  A400: '#5b93ff',
  A700: '#4182ff',
}

export const assurantTheme = createTheme({
  palette: {
    primary: {
      main:         assurantBlue[500],
      light:        assurantBlue[200],
      dark:         assurantBlue[700],
      contrastText: '#ffffff',
    },
    secondary: {
      main: '#ffa000',
    },
    success: {
      main: '#2e7d32',
    },
    background: {
      default: '#f5f8fd',
      paper:   '#ffffff',
    },
    text: {
      primary:   '#44464b',
      secondary: '#666666',
    },
    error: { main: '#d32f2f' },
  },
  typography: {
    fontFamily: "Roboto, 'Helvetica Neue', sans-serif",
    h5: { fontWeight: 600, color: '#44464b' },
    h6: { fontWeight: 600, color: '#44464b' },
    subtitle2: { color: '#44464b' },
  },
  components: {
    MuiAppBar: {
      styleOverrides: {
        root: {
          backgroundColor: '#ffffff',
          color:           '#44464b',
          boxShadow:       '0 5px 15px rgba(0,0,0,0.15)',
        },
      },
    },
    MuiCard: {
      styleOverrides: {
        root: {
          borderRadius: 4,
          boxShadow: '0 2px 8px rgba(0,0,0,0.08)',
        },
      },
    },
    MuiTableRow: {
      styleOverrides: {
        root: {
          cursor: 'pointer',
          '&:hover': { backgroundColor: 'rgba(0,0,0,0.04)' },
        },
      },
    },
    MuiButton: {
      styleOverrides: {
        root: { textTransform: 'none', fontWeight: 500 },
      },
    },
    MuiChip: {
      styleOverrides: {
        root: { fontWeight: 500 },
      },
    },
  },
})
