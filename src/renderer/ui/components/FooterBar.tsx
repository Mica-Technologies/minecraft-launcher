import { Stack } from '@fluentui/react';
import '../../css/components/FooterBar.css';
import React, { ReactNode } from 'react';

type FooterBarProps = {
  children?: ReactNode | ReactNode[];
};

export default function FooterBar({ children }: FooterBarProps) {
  return (
    <Stack.Item className="footerBar">
      <Stack
        horizontal
        horizontalAlign="space-between"
        verticalAlign="center"
        className="footerBarStack"
      >
        {React.Children.map(children, (child) => (
          <Stack.Item>{child}</Stack.Item>
        ))}
      </Stack>
    </Stack.Item>
  );
}

FooterBar.defaultProps = {
  children: [],
};
