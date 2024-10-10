import { HashRouter, Route, Routes } from 'react-router-dom';
import Main from './pages/Main';
import About from './pages/About';

function App(): JSX.Element {
  return (
    <HashRouter>
      <Routes>
        <Route path="/" element={<Main />} />
        <Route path="/about" element={<About />} />
      </Routes>
    </HashRouter>
  );
}

export default App;
