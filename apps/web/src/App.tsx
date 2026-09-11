import { AuthProvider } from './auth/AuthProvider.tsx'
import { useAuth } from './auth/useAuth.ts'
import { MainDashboard } from './dashboard-v2/MainDashboard.tsx'
import { useButtonClickSound } from './hooks/useButtonClickSound.ts'
import { LoginPage } from './pages/LoginPage.tsx'

function Screen() {
  const { status } = useAuth()

  if (status === 'booting') {
    return (
      <div className="boot" role="status" aria-live="polite">
        <span className="spinner spinner--light" aria-hidden="true" />
        <span>正在恢复登录状态…</span>
      </div>
    )
  }
  return status === 'authenticated' ? <MainDashboard /> : <LoginPage />
}

function App() {
  useButtonClickSound()

  return (
    <AuthProvider>
      <Screen />
    </AuthProvider>
  )
}

export default App
