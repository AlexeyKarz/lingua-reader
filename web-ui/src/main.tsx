import React from 'react'
import ReactDOM from 'react-dom/client'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createBrowserRouter, RouterProvider } from 'react-router-dom'
import App from './App'
import AdminIngestion from './pages/AdminIngestion'
import Catalog from './pages/Catalog'
import Reader from './pages/Reader'
import Vocabulary from './pages/Vocabulary'
import './index.css'

const qc = new QueryClient()
const router = createBrowserRouter([
    { path: '/', element: <App />, children: [
            { index: true, element: <Catalog/> },
            { path: 'admin/ingest', element: <AdminIngestion/> },
            { path: 'reader', element: <Reader/> },
            { path: 'vocabulary', element: <Vocabulary/> },
        ]},
])

ReactDOM.createRoot(document.getElementById('root')!).render(
    <React.StrictMode>
        <QueryClientProvider client={qc}>
            <RouterProvider router={router}/>
        </QueryClientProvider>
    </React.StrictMode>
)
