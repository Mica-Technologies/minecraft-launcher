import * as React from 'react';
import CssBaseline from '@mui/material/CssBaseline';
import { HashRouter, Route, Routes } from 'react-router-dom';
import Home from './pages/Home';
import About from './pages/About';
import RootUiStack from './components/containers/RootUiStack';
import AppTheme from '@renderer/components/shared-theme/AppTheme';
import Installs from '@renderer/pages/Installs';
import Discover from '@renderer/pages/Discover';
import Configure from '@renderer/pages/Configure';
import ErrorView from '@renderer/pages/ErrorView';
import Account from '@renderer/pages/Account';

const MISSING_ERROR_CODE: string = '404';
const MISSING_ERROR_MESSAGE: string = 'The page you are looking for does not exist.';

function App(props: { disableCustomTheme?: boolean }): JSX.Element {
  return (
    <React.Fragment>
      <HashRouter>
        <AppTheme {...props}>
          <CssBaseline enableColorScheme />
          <RootUiStack>
            <Routes>
              <Route path="/" element={<Home />} />
              <Route path="/installs" element={<Installs />} />
              <Route path="/discover" element={<Discover />} />
              <Route path="/configure" element={<Configure />} />
              <Route path="/about" element={<About />} />
              <Route path="/account" element={<Account />} />
              <Route
                path="*"
                element={<ErrorView code={MISSING_ERROR_CODE} message={MISSING_ERROR_MESSAGE} />}
              />
            </Routes>
          </RootUiStack>
        </AppTheme>
      </HashRouter>
    </React.Fragment>
  );
}

export default App;
