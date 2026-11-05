import { Camera, GeoJSONSource, Layer, Map } from '@maplibre/maplibre-react-native';
import type { Itinerary, Leg } from '@tm/core';
import { useLocalSearchParams } from 'expo-router';
import { useState } from 'react';
import { Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';

import { RouteBadge, routeSystemLabel } from '@/components/route-badge';
import { useTripForm } from '@/state/trip-form';
import { clock, distance, duration, hex } from '@/ui/format';
import { colors, radius, space, type } from '@/ui/theme';

const MAP_STYLE = 'https://tiles.openfreemap.org/styles/liberty';

function tripGeoJSON(itinerary: Itinerary): GeoJSON.FeatureCollection {
  return {
    type: 'FeatureCollection',
    features: itinerary.legs
      .filter((l) => l.geometry.length > 1)
      .map((leg) => ({
        type: 'Feature',
        properties: { color: leg.mode === 'walk' ? colors.walk : hex(leg.route.color), walk: leg.mode === 'walk' },
        geometry: { type: 'LineString', coordinates: leg.geometry.map((c) => [c.lon, c.lat]) },
      })),
  };
}

function bounds(itinerary: Itinerary): [number, number, number, number] | undefined {
  const pts = itinerary.legs.flatMap((l) => (l.geometry.length ? l.geometry : [l.from, l.to]));
  if (!pts.length) return undefined;
  const lons = pts.map((p) => p.lon);
  const lats = pts.map((p) => p.lat);
  return [Math.min(...lons), Math.min(...lats), Math.max(...lons), Math.max(...lats)];
}

function TransitLegView({ leg }: { leg: Extract<Leg, { mode: 'transit' }> }) {
  const [open, setOpen] = useState(false);
  const color = hex(leg.route.color);
  const stops = leg.intermediateStops.length;
  return (
    <View style={styles.leg}>
      <View style={[styles.rail, { backgroundColor: color }]} />
      <View style={styles.legBody}>
        <View style={styles.stopLine}>
          <Text style={styles.time}>{clock(leg.departure)}</Text>
          <Text style={[type.body, styles.stopName]} numberOfLines={2}>
            {leg.from.name}
          </Text>
        </View>
        <View style={styles.vehicle}>
          <RouteBadge route={leg.route} large />
          <View style={{ flex: 1 }}>
            <Text style={type.body} numberOfLines={1}>
              Hacia {leg.headsign && leg.headsign !== leg.route.shortName ? leg.headsign : leg.to.name}
            </Text>
            <Text style={type.caption}>
              {routeSystemLabel(leg.route)} · {duration(leg.departure, leg.arrival)}
            </Text>
          </View>
        </View>
        {stops > 0 && (
          <Pressable onPress={() => setOpen((o) => !o)} hitSlop={6}>
            <Text style={styles.toggle}>
              {open ? 'Ocultar' : 'Ver'} {stops} parada{stops > 1 ? 's' : ''} intermedia{stops > 1 ? 's' : ''}
            </Text>
          </Pressable>
        )}
        {open &&
          leg.intermediateStops.map((s, i) => (
            <View key={i} style={styles.stopLine}>
              <Text style={[styles.time, styles.minor]}>{clock(s.departure)}</Text>
              <Text style={[type.caption, styles.stopName]}>{s.place.name}</Text>
            </View>
          ))}
        <View style={styles.stopLine}>
          <Text style={styles.time}>{clock(leg.arrival)}</Text>
          <Text style={[type.body, styles.stopName]} numberOfLines={2}>
            {leg.to.name}
          </Text>
        </View>
      </View>
    </View>
  );
}

function WalkLegView({ leg }: { leg: Extract<Leg, { mode: 'walk' }> }) {
  return (
    <View style={styles.leg}>
      <View style={styles.walkRail} />
      <View style={[styles.legBody, { paddingVertical: space.sm }]}>
        <Text style={type.caption}>
          🚶 Caminar {duration(leg.departure, leg.arrival)} · {distance(leg.distance)}
          {leg.estimated ? ' (aprox.)' : ''}
        </Text>
      </View>
    </View>
  );
}

export default function TripScreen() {
  const { id } = useLocalSearchParams<{ id: string }>();
  const { result } = useTripForm();
  const itinerary = result?.itineraries.find((it) => it.id === id);
  if (!itinerary) return <Text style={[type.caption, { padding: space.xl }]}>Este viaje ya no está disponible.</Text>;
  const b = bounds(itinerary);

  return (
    <ScrollView style={{ flex: 1 }} contentContainerStyle={{ paddingBottom: space.xl }}>
      <Map style={styles.map} mapStyle={MAP_STYLE}>
        {b && <Camera initialViewState={{ bounds: b, padding: { top: 40, bottom: 40, left: 40, right: 40 } }} />}
        <GeoJSONSource id="trip" data={tripGeoJSON(itinerary)}>
          <Layer
            id="trip-line"
            type="line"
            paint={{ 'line-color': ['get', 'color'], 'line-width': 5 }}
            layout={{ 'line-cap': 'round', 'line-join': 'round' }}
          />
        </GeoJSONSource>
      </Map>

      <View style={styles.summary}>
        <Text style={type.title}>
          {clock(itinerary.departure)} – {clock(itinerary.arrival)}
        </Text>
        <Text style={type.caption}>
          {duration(itinerary.departure, itinerary.arrival)} ·{' '}
          {itinerary.transfers === 0 ? 'Directo' : `${itinerary.transfers} transbordo${itinerary.transfers > 1 ? 's' : ''}`}
          {result?.source === 'device' ? ' · horario offline' : ''}
        </Text>
      </View>

      <View style={styles.timeline}>
        {itinerary.legs.map((leg, i) =>
          leg.mode === 'walk' ? <WalkLegView key={i} leg={leg} /> : <TransitLegView key={i} leg={leg} />,
        )}
      </View>
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  map: { height: 240 },
  summary: { backgroundColor: colors.surface, padding: space.lg, gap: space.xs },
  timeline: { backgroundColor: colors.surface, marginTop: space.sm, paddingVertical: space.md, paddingHorizontal: space.lg },
  leg: { flexDirection: 'row', gap: space.md },
  rail: { width: 6, borderRadius: 3 },
  walkRail: { width: 0, marginLeft: 2, borderLeftWidth: 2, borderStyle: 'dashed', borderColor: colors.walk },
  legBody: { flex: 1, gap: space.sm, paddingVertical: space.xs },
  stopLine: { flexDirection: 'row', gap: space.md, alignItems: 'flex-start' },
  time: { width: 52, fontSize: 15, fontWeight: '700', color: colors.text, fontVariant: ['tabular-nums'] },
  minor: { fontWeight: '400', color: colors.textMuted, fontSize: 13 },
  stopName: { flex: 1 },
  vehicle: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: space.md,
    padding: space.md,
    borderRadius: radius.md,
    backgroundColor: colors.background,
  },
  toggle: { color: colors.brand, fontWeight: '600', fontSize: 14 },
});
