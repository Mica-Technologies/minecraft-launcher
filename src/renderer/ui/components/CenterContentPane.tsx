import '../../css/components/CenterContentPane.css';
import React, { ReactNode } from 'react';
import { Stack } from '@fluentui/react';

type CenterContentPaneProps = {
  children: ReactNode | ReactNode[];
};

export default function CenterContentPane({
  children,
}: CenterContentPaneProps) {
  return (
    <Stack
      verticalFill
      verticalAlign="center"
      horizontalAlign="center"
      className="centerContentPane"
    >
      {children}
    </Stack>
  );
}
