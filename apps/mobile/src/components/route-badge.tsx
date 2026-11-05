import type { Route } from '@tm/core';
import { StyleSheet, Text, View } from 'react-native';

import { hex, onColor } from '@/ui/format';

const systemLabel: Record<Route['system'], string> = {
  troncal: 'TransMilenio',
  alimentador: 'Alimentador',
  urbano: 'SITP',
  complementario: 'SITP',
  especial: 'SITP',
  dual: 'Dual',
  cable: 'TransMiCable',
  unknown: '',
};

export function routeSystemLabel(route: Route): string {
  return systemLabel[route.system];
}

export function RouteBadge({ route, large }: { route: Route; large?: boolean }) {
  const bg = hex(route.color);
  return (
    <View style={[styles.badge, large && styles.large, { backgroundColor: bg }]}>
      <Text style={[styles.text, large && styles.largeText, { color: onColor(bg) }]} numberOfLines={1}>
        {route.shortName || '?'}
      </Text>
    </View>
  );
}

const styles = StyleSheet.create({
  badge: { borderRadius: 4, paddingHorizontal: 6, paddingVertical: 2, minWidth: 30, alignItems: 'center' },
  large: { paddingHorizontal: 8, paddingVertical: 4, minWidth: 40 },
  text: { fontSize: 13, fontWeight: '700' },
  largeText: { fontSize: 15 },
});
