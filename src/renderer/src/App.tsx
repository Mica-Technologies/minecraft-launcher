import * as React from 'react';
import CssBaseline from '@mui/material/CssBaseline';
import { HashRouter, Route, Routes } from 'react-router-dom';
import Main from './pages/Main';
import About from './pages/About';

function App(): JSX.Element {
  return (
    <React.Fragment>
      <CssBaseline />
      <HashRouter>
        <Routes>
          <Route path="/" element={<Main />} />
          <Route path="/about" element={<About />} />
        </Routes>
      </HashRouter>
    </React.Fragment>
  );
}

export default App;
