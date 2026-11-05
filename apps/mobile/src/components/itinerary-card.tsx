import type { Itinerary, Leg } from '@tm/core';
import { Pressable, StyleSheet, Text, View } from 'react-native';

import { RouteBadge } from '@/components/route-badge';
import { clock, duration, hex, minutesBetween } from '@/ui/format';
import { colors, radius, space, type } from '@/ui/theme';

/** Proportional bar showing each leg's share of the trip, like SBB's connection overview. */
function LegBar({ itinerary }: { itinerary: Itinerary }) {
  const total = Math.max(1, minutesBetween(itinerary.departure, itinerary.arrival));
  return (
    <View style={styles.bar}>
      {itinerary.legs.map((leg, i) => (
        <View
          key={i}
          style={[
            styles.segment,
            { flex: Math.max(minutesBetween(leg.departure, leg.arrival), total * 0.04) },
            leg.mode === 'walk' ? styles.walkSegment : { backgroundColor: legColor(leg) },
          ]}
        />
      ))}
    </View>
  );
}

const legColor = (leg: Leg) => (leg.mode === 'walk' ? colors.walk : hex(leg.route.color));

export function ItineraryCard({ itinerary, onPress }: { itinerary: Itinerary; onPress: () => void }) {
  const transit = itinerary.legs.filter((l) => l.mode === 'transit');
  // Only mention the boarding stop when it differs from where the trip starts.
  const first = transit[0] && transit[0].from.name !== itinerary.legs[0]?.from.name ? transit[0] : undefined;
  const walkMin = itinerary.legs
    .filter((l) => l.mode === 'walk')
    .reduce((s, l) => s + minutesBetween(l.departure, l.arrival), 0);

  return (
    <Pressable
      onPress={onPress}
      style={({ pressed }) => [styles.card, pressed && { backgroundColor: colors.background }]}
      accessibilityRole="button"
      accessibilityLabel={`Salida ${clock(itinerary.departure)}, llegada ${clock(itinerary.arrival)}, ${duration(itinerary.departure, itinerary.arrival)}`}
    >
      <View style={styles.top}>
        <View style={styles.badges}>
          {transit.map((l, i) => (
            <RouteBadge key={i} route={l.route} />
          ))}
          {transit.length === 0 && <Text style={type.caption}>Solo a pie</Text>}
        </View>
        <Text style={styles.duration}>{duration(itinerary.departure, itinerary.arrival)}</Text>
      </View>

      <View style={styles.times}>
        <Text style={type.time}>{clock(itinerary.departure)}</Text>
        <LegBar itinerary={itinerary} />
        <Text style={type.time}>{clock(itinerary.arrival)}</Text>
      </View>

      <Text style={type.caption} numberOfLines={1}>
        {first ? `Desde ${first.from.name}` : ''}
        {first ? ' · ' : ''}
        {itinerary.transfers === 0 ? 'Directo' : `${itinerary.transfers} transbordo${itinerary.transfers > 1 ? 's' : ''}`}
        {walkMin > 0 ? ` · ${walkMin} min a pie` : ''}
      </Text>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  card: {
    backgroundColor: colors.surface,
    borderRadius: radius.md,
    padding: space.lg,
    gap: space.sm,
  },
  top: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' },
  badges: { flexDirection: 'row', gap: space.xs, flexShrink: 1, flexWrap: 'wrap' },
  duration: { fontSize: 14, fontWeight: '600', color: colors.textMuted },
  times: { flexDirection: 'row', alignItems: 'center', gap: space.md },
  bar: { flex: 1, flexDirection: 'row', height: 6, gap: 2 },
  segment: { borderRadius: 3 },
  walkSegment: { backgroundColor: colors.border },
});
