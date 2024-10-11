import * as React from 'react';
import CssBaseline from '@mui/material/CssBaseline';
import { HashRouter, Route, Routes } from 'react-router-dom';
import Main from './pages/Main';
import About from './pages/About';
import RootUiStack from './components/containers/RootUiStack';

function App(): JSX.Element {
  return (
    <React.Fragment>
      <CssBaseline enableColorScheme />
      <RootUiStack>
        <HashRouter>
          <Routes>
            <Route path="/" element={<Main />} />
            <Route path="/about" element={<About />} />
          </Routes>
        </HashRouter>
      </RootUiStack>
    </React.Fragment>
  );
}

export default App;
