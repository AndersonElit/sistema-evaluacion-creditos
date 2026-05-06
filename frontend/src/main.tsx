import React from 'react';
import ReactDOM from 'react-dom/client';
import { App } from './app/App';
import { keycloak, initKeycloak } from '@/shared/lib/keycloak';
import './styles/globals.css';

initKeycloak()
  .then((authenticated) => {
    if (!authenticated) {
      keycloak.login();
      return;
    }
    keycloak.onTokenExpired = () => {
      keycloak.updateToken(30).catch(() => keycloak.logout());
    };
    ReactDOM.createRoot(document.getElementById('root')!).render(
      <React.StrictMode>
        <App />
      </React.StrictMode>,
    );
  })
  .catch((err) => {
    console.error('Keycloak init failed', err);
    document.body.innerHTML =
      '<div style="padding:2rem;font-family:system-ui">No se pudo inicializar la autenticación.</div>';
  });
