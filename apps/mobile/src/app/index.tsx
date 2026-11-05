import { Camera, Map } from '@maplibre/maplibre-react-native';
import * as Location from 'expo-location';
import { router } from 'expo-router';
import { useEffect, useState } from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import { useBundleStatus, useConnectivity } from '@/data';
import { useTripForm, type Field } from '@/state/trip-form';
import { clock, dayLabel, shortDate } from '@/ui/format';
import { colors, radius, space, type } from '@/ui/theme';

const MAP_STYLE = 'https://tiles.openfreemap.org/styles/liberty';
const BOGOTA = { center: [-74.08, 4.65] as [number, number], zoom: 11 };

const TIME_OPTIONS: { label: string; minutes?: number }[] = [
  { label: 'Ahora' },
  { label: '+15 min', minutes: 15 },
  { label: '+30 min', minutes: 30 },
  { label: '+1 h', minutes: 60 },
];

function useCurrentLocationAsOrigin() {
  const { from, setPlace } = useTripForm();
  useEffect(() => {
    if (from) return;
    void (async () => {
      const { granted } = await Location.requestForegroundPermissionsAsync();
      if (!granted) return;
      // Last known position is instant; a fresh fix can take seconds or never come indoors.
      const pos =
        (await Location.getLastKnownPositionAsync()) ??
        (await Location.getCurrentPositionAsync({ accuracy: Location.Accuracy.Balanced }));
      setPlace('from', {
        id: 'current-location',
        name: 'Mi ubicación',
        kind: 'coordinate',
        lat: pos.coords.latitude,
        lon: pos.coords.longitude,
      });
    })().catch(() => {});
    // Only on first mount; the user can change it afterwards.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);
}

function PlaceRow({ field, label }: { field: Field; label: string }) {
  const form = useTripForm();
  const place = form[field];
  return (
    <Pressable
      style={({ pressed }) => [styles.placeRow, pressed && styles.pressed]}
      onPress={() => router.push({ pathname: '/search', params: { field } })}
      accessibilityRole="button"
      accessibilityLabel={`${label}: ${place?.name ?? 'sin elegir'}`}
    >
      <View style={[styles.marker, field === 'to' && styles.markerTo]} />
      <View style={{ flex: 1 }}>
        <Text style={styles.fieldLabel}>{label}</Text>
        <Text style={[type.body, !place && { color: colors.textFaint }]} numberOfLines={1}>
          {place?.name ?? (field === 'from' ? '¿Desde dónde?' : '¿A dónde vas?')}
        </Text>
      </View>
    </Pressable>
  );
}

function TimeSelector() {
  const { time, arriveBy, setTime } = useTripForm();
  const [preset, setPreset] = useState<string>('Ahora');
  return (
    <View style={{ gap: space.sm }}>
      <View style={styles.segmented}>
        {[false, true].map((value) => (
          <Pressable
            key={String(value)}
            onPress={() => setTime(time, value)}
            style={[styles.segment, arriveBy === value && styles.segmentActive]}
          >
            <Text style={[styles.segmentText, arriveBy === value && styles.segmentTextActive]}>
              {value ? 'Llegar a las' : 'Salir a las'}
            </Text>
          </Pressable>
        ))}
      </View>
      <View style={styles.chips}>
        {TIME_OPTIONS.map((opt) => {
          const active = preset === opt.label;
          return (
            <Pressable
              key={opt.label}
              onPress={() => {
                setPreset(opt.label);
                setTime(opt.minutes ? new Date(Date.now() + opt.minutes * 60000) : undefined, arriveBy);
              }}
              style={[styles.chip, active && styles.chipActive]}
            >
              <Text style={[styles.chipText, active && styles.chipTextActive]}>{opt.label}</Text>
            </Pressable>
          );
        })}
      </View>
      <Text style={type.caption}>
        {dayLabel(time ?? new Date())} · {clock((time ?? new Date()).toISOString())}
      </Text>
    </View>
  );
}

function DataStatus() {
  const bundle = useBundleStatus();
  const net = useConnectivity();
  let text: string;
  if (bundle.status === 'downloading') text = `Descargando horarios… ${Math.round((bundle.progress ?? 0) * 100)}%`;
  else if (bundle.status === 'checking') text = 'Buscando actualizaciones…';
  else if (bundle.activeVersion) text = `Horarios offline listos${bundle.activeBuiltAt ? ` · ${shortDate(bundle.activeBuiltAt)}` : ''}`;
  else if (bundle.downloadDeferred) text = 'Conéctate a Wi-Fi para descargar los horarios offline';
  else text = 'Horarios offline aún no descargados';
  return (
    <View style={styles.status}>
      <View style={[styles.statusDot, { backgroundColor: net.internetReachable ? colors.online : colors.offline }]} />
      <Text style={type.caption} numberOfLines={1}>
        {net.internetReachable ? 'En línea' : 'Sin conexión'} · {text}
      </Text>
    </View>
  );
}

export default function HomeScreen() {
  const { from, to, swap } = useTripForm();
  useCurrentLocationAsOrigin();
  const ready = Boolean(from && to);

  return (
    <View style={styles.container}>
      <Map style={StyleSheet.absoluteFill} mapStyle={MAP_STYLE} attributionPosition={{ bottom: 8, left: 8 }}>
        <Camera initialViewState={BOGOTA} />
      </Map>
      <SafeAreaView style={styles.overlay} pointerEvents="box-none">
        <View style={styles.panel}>
          <Text style={type.title}>¿A dónde vas?</Text>
          <View style={styles.places}>
            <PlaceRow field="from" label="Desde" />
            <View style={styles.divider} />
            <PlaceRow field="to" label="Hasta" />
            <Pressable style={styles.swap} onPress={swap} accessibilityLabel="Intercambiar origen y destino" hitSlop={8}>
              <Text style={styles.swapText}>⇅</Text>
            </Pressable>
          </View>
          <TimeSelector />
          <Pressable
            disabled={!ready}
            onPress={() => router.push('/results')}
            style={({ pressed }) => [styles.button, !ready && styles.buttonDisabled, pressed && { backgroundColor: colors.brandPressed }]}
          >
            <Text style={styles.buttonText}>Buscar conexiones</Text>
          </Pressable>
          <DataStatus />
        </View>
      </SafeAreaView>
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1 },
  overlay: { position: 'absolute', top: 0, left: 0, right: 0 },
  panel: {
    margin: space.md,
    padding: space.lg,
    gap: space.lg,
    borderRadius: radius.lg,
    backgroundColor: colors.surface,
    shadowColor: '#000',
    shadowOpacity: 0.12,
    shadowRadius: 12,
    elevation: 6,
  },
  places: { borderWidth: 1, borderColor: colors.border, borderRadius: radius.md },
  placeRow: { flexDirection: 'row', alignItems: 'center', gap: space.md, paddingVertical: space.md, paddingLeft: space.md, paddingRight: 56 },
  pressed: { backgroundColor: colors.background },
  marker: { width: 10, height: 10, borderRadius: 5, borderWidth: 2, borderColor: colors.text },
  markerTo: { backgroundColor: colors.brand, borderColor: colors.brand },
  fieldLabel: { fontSize: 12, color: colors.textMuted },
  divider: { height: 1, backgroundColor: colors.border, marginLeft: 34 },
  swap: {
    position: 'absolute',
    right: space.md,
    top: '50%',
    marginTop: -20,
    width: 40,
    height: 40,
    borderRadius: 20,
    borderWidth: 1,
    borderColor: colors.border,
    backgroundColor: colors.surface,
    alignItems: 'center',
    justifyContent: 'center',
  },
  swapText: { fontSize: 20, color: colors.text },
  segmented: { flexDirection: 'row', backgroundColor: colors.background, borderRadius: radius.sm, padding: 3 },
  segment: { flex: 1, paddingVertical: 8, alignItems: 'center', borderRadius: radius.sm - 2 },
  segmentActive: { backgroundColor: colors.surface, elevation: 1, shadowColor: '#000', shadowOpacity: 0.08, shadowRadius: 2 },
  segmentText: { fontSize: 14, color: colors.textMuted, fontWeight: '500' },
  segmentTextActive: { color: colors.text, fontWeight: '600' },
  chips: { flexDirection: 'row', gap: space.sm },
  chip: { paddingHorizontal: space.md, paddingVertical: 6, borderRadius: 16, borderWidth: 1, borderColor: colors.border },
  chipActive: { backgroundColor: colors.text, borderColor: colors.text },
  chipText: { fontSize: 14, color: colors.text },
  chipTextActive: { color: colors.surface, fontWeight: '600' },
  button: { backgroundColor: colors.brand, borderRadius: radius.md, paddingVertical: 14, alignItems: 'center' },
  buttonDisabled: { backgroundColor: '#E8A3AE' },
  buttonText: { color: '#fff', fontSize: 16, fontWeight: '700' },
  status: { flexDirection: 'row', alignItems: 'center', gap: space.sm },
  statusDot: { width: 8, height: 8, borderRadius: 4 },
});
