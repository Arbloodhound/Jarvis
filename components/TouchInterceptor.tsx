import React from 'react';
import { View } from 'react-native';

interface Props {
  children: React.ReactNode;
}

export default function TouchInterceptor({ children }: Props) {
  return (
    <View style={{ flex: 1 }}>
      {children}
    </View>
  );
}
