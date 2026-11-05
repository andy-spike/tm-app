import { Stack } from 'expo-router';
import { StatusBar } from 'expo-status-bar';

import { TripFormProvider } from '@/state/trip-form';
import { colors } from '@/ui/theme';

export default function RootLayout() {
  return (
    <TripFormProvider>
      <StatusBar style="dark" />
      <Stack
        screenOptions={{
          headerTintColor: colors.text,
          headerTitleStyle: { fontWeight: '600' },
          headerShadowVisible: false,
          headerStyle: { backgroundColor: colors.surface },
          contentStyle: { backgroundColor: colors.background },
        }}
      >
        <Stack.Screen name="index" options={{ headerShown: false }} />
        <Stack.Screen name="search" options={{ presentation: 'modal', title: 'Buscar lugar' }} />
        <Stack.Screen name="results" options={{ title: 'Conexiones' }} />
        <Stack.Screen name="trip/[id]" options={{ title: 'Detalle del viaje' }} />
      </Stack>
    </TripFormProvider>
  );
}
