import '@renderer/assets/css/components/RootUiStack.css';
import { ReactNode } from 'react';
import Stack from '@mui/material/Stack'
import backgroundImage from '@renderer/assets/background-devtemp.png'

type RootUiStackProps = {
  children: ReactNode | ReactNode[];
};

export default function RootUiStack({ children }: RootUiStackProps) {
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
      {children}
    </Stack>
  );
}