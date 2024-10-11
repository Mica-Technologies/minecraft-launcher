import '@renderer/assets/css/components/RootUiStack.css';
import { ReactNode } from 'react';
import Stack from '@mui/material/Stack';
import backgroundImage from '@renderer/assets/background-devtemp.png';
import FootBar from '../views/FootBar';
import TitleBar from '../views/TitleBar';

type RootUiStackProps = {
  children: ReactNode | ReactNode[];
};

export default function RootUiStack({ children }: RootUiStackProps): JSX.Element {
  return (
    <Stack
      spacing={2}
      justifyContent="center"
      alignItems="center"
      className="RootUiStack"
      style={{
        backgroundImage: `url(${backgroundImage})`,
      }}
    >
      <TitleBar>
        <span className="draggable">Testing</span>
      </TitleBar>
      {children}
      <FootBar />
    </Stack>
  );
}
