import { ActivityIndicator, StyleSheet, Text, View } from 'react-native';

import { shortDate } from '@/ui/format';
import { colors, radius, space } from '@/ui/theme';

/** Tells the user whether results come from the live server or the offline timetable. */
export function SourcePill({
  source,
  upgrading,
  builtAt,
}: {
  source?: 'server' | 'device';
  upgrading: boolean;
  builtAt?: string;
}) {
  if (!source) return null;
  const online = source === 'server';
  const date = builtAt ? shortDate(builtAt, false) : undefined;
  return (
    <View style={[styles.pill, { backgroundColor: online ? '#E7F5EC' : colors.offlineSurface }]}>
      {upgrading ? (
        <ActivityIndicator size="small" color={colors.offline} />
      ) : (
        <View style={[styles.dot, { backgroundColor: online ? colors.online : colors.offline }]} />
      )}
      <Text style={[styles.text, { color: online ? colors.online : colors.offline }]}>
        {online
          ? 'Rutas en línea'
          : upgrading
            ? 'Horario offline · buscando mejores rutas…'
            : `Horario offline${date ? ` · datos ${date}` : ''}`}
      </Text>
    </View>
  );
}

const styles = StyleSheet.create({
  pill: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: space.sm,
    alignSelf: 'flex-start',
    paddingHorizontal: space.md,
    paddingVertical: 6,
    borderRadius: radius.lg,
  },
  dot: { width: 8, height: 8, borderRadius: 4 },
  text: { fontSize: 13, fontWeight: '600' },
});
