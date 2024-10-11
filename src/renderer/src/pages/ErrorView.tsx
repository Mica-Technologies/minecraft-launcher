import Stack from '@mui/material/Stack';
import ErrorIcon from '@mui/icons-material/Error';
import Button from '@mui/material/Button';
import { useNavigate } from 'react-router';

interface ErrorViewProps {
  code?: string;
  message?: string;
  trace?: string;
}

const defaultErrorViewProps: ErrorViewProps = {
  code: 'UNKNOWN',
  message: 'An unknown error has occurred.',
  trace: '',
};

function ErrorView(props: ErrorViewProps): JSX.Element {
  const { code, message, trace } = { ...defaultErrorViewProps, ...props };
  const navigate = useNavigate();
  // Vertically and horizontally center the content
  // Error logo above code and message, optional trace below message if provided/non-empty
  return (
    <Stack direction="row" alignItems="center" justifyContent="space-between">
      <Stack direction="column" alignItems="center" justifyContent="center" spacing={2}>
        <ErrorIcon style={{ fontSize: 64, color: 'red' }} />
        <h1>Error {code}</h1>
        <p>{message}</p>
        {trace && trace.trim() !== '' && (
          <pre
            style={{
              backgroundColor: '#f5f5f5',
              padding: '10px',
              borderRadius: '5px',
              maxHeight: '200px',
              overflowY: 'auto',
              width: '80%',
            }}
          >
            {trace}
          </pre>
        )}
        <Stack direction="row" alignItems="center" justifyContent="center" spacing={2}>
          <a target="_blank" rel="noreferrer" onClick={(): void => navigate('/')}>
            <Button variant="outlined">Go Back to Home</Button>
          </a>
          <a
            target="_blank"
            rel="noreferrer"
            onClick={async (): Promise<void> => await window.api.shutdownApp()}
          >
            <Button variant="outlined">Quit App</Button>
          </a>
          <a
            target="_blank"
            rel="noreferrer"
            onClick={async (): Promise<void> => await window.api.restartApp()}
          >
            <Button variant="outlined">Restart App</Button>
          </a>
        </Stack>
      </Stack>
    </Stack>
  );
}

export default ErrorView;
