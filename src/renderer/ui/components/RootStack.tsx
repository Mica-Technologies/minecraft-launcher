import '../../css/components/RootStack.css';
import React, { ReactNode } from 'react';
import { Stack } from '@fluentui/react';
import background from '../../../../assets/ui/background-devtemp.png';

type RootStackProps = {
  children: ReactNode | ReactNode[];
};

export default function RootStack({ children }: RootStackProps) {
  return (
    <Stack
      verticalFill
      verticalAlign="center"
      className="rootStack"
      horizontalAlign="center"
      style={{ backgroundImage: `url(${background})` }}
    >
      {children}
    </Stack>
  );
}
