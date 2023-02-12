import { MemoryRouter as Router, Route, Routes } from 'react-router-dom';
import React from 'react';
import { ThemeProvider } from '@fluentui/react';
import { Fluent2WebDarkTheme } from '@fluentui/fluent2-theme';
import { FluentProvider } from '@fluentui/react-components';
import { createV9Theme } from '@fluentui/react-migration-v8-v9';
import MainUi from './ui/MainUi';

export default function App() {
  return (
    <ThemeProvider
      theme={Fluent2WebDarkTheme}
      style={{ width: '100%', height: '100%' }}
    >
      <FluentProvider
        theme={createV9Theme(Fluent2WebDarkTheme)}
        style={{ width: '100%', height: '100%' }}
      >
        <Router>
          <Routes>
            <Route path="/" element={<MainUi />} />
          </Routes>
        </Router>
      </FluentProvider>
    </ThemeProvider>
  );
}
