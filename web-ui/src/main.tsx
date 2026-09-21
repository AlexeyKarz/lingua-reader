import React from 'react'
import ReactDOM from 'react-dom/client'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createBrowserRouter, RouterProvider } from 'react-router-dom'
import App from './App'
import { AuthProvider, RequireAdmin } from './auth'
import AdminDashboard from './pages/AdminDashboard'
import AdminIngestion from './pages/AdminIngestion'
import Catalog from './pages/Catalog'
import Login from './pages/Login'
import Reader from './pages/Reader'
import Vocabulary from './pages/Vocabulary'
import './index.css'

const qc = new QueryClient()
const router = createBrowserRouter([
    { path: '/', element: <App />, children: [
            { index: true, element: <Catalog/> },
            { path: 'admin', element: <RequireAdmin><AdminDashboard/></RequireAdmin> },
            { path: 'admin/metrics', element: <RequireAdmin><AdminDashboard/></RequireAdmin> },
            { path: 'admin/ingest', element: <RequireAdmin><AdminIngestion/></RequireAdmin> },
            { path: 'login', element: <Login/> },
            { path: 'reader', element: <Reader/> },
            { path: 'vocabulary', element: <Vocabulary/> },
        ]},
])

ReactDOM.createRoot(document.getElementById('root')!).render(
    <React.StrictMode>
        <QueryClientProvider client={qc}>
            <AuthProvider>
                <RouterProvider router={router}/>
            </AuthProvider>
        </QueryClientProvider>
    </React.StrictMode>
)
