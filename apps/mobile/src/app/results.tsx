import type { TripRequest } from '@tm/core';
import { router } from 'expo-router';
import { useEffect, useState } from 'react';
import { ActivityIndicator, FlatList, StyleSheet, Text, View } from 'react-native';

import { ItineraryCard } from '@/components/itinerary-card';
import { SourcePill } from '@/components/source-pill';
import { useTripPlanner } from '@/data';
import { useTripForm } from '@/state/trip-form';
import { bogotaIso, clock } from '@/ui/format';
import { colors, space, type } from '@/ui/theme';

export default function ResultsScreen() {
  const { from, to, time, arriveBy, setResult } = useTripForm();
  // Freeze "now" when the screen opens so the request (and its cache key) stays stable.
  const [request] = useState<TripRequest | undefined>(() =>
    from && to ? { from, to, time: bogotaIso(time ?? new Date()), arriveBy } : undefined,
  );
  const { result, isLoading, isUpgrading, error, source } = useTripPlanner(request);

  useEffect(() => setResult(result), [result, setResult]);

  if (!request) return null;
  const itineraries = result?.itineraries ?? [];

  return (
    <View style={styles.container}>
      <View style={styles.header}>
        <Text style={type.heading} numberOfLines={1}>
          {request.from.name} → {request.to.name}
        </Text>
        <Text style={type.caption}>
          {arriveBy ? 'Llegada' : 'Salida'} {clock(request.time)}
        </Text>
        <SourcePill source={source} upgrading={isUpgrading} builtAt={result?.dataBuiltAt} />
      </View>

      {isLoading ? (
        <View style={styles.center}>
          <ActivityIndicator color={colors.brand} size="large" />
          <Text style={type.caption}>Buscando conexiones…</Text>
        </View>
      ) : (
        <FlatList
          data={itineraries}
          keyExtractor={(it) => it.id}
          contentContainerStyle={styles.list}
          ItemSeparatorComponent={() => <View style={{ height: space.sm }} />}
          renderItem={({ item }) => (
            <ItineraryCard itinerary={item} onPress={() => router.push({ pathname: '/trip/[id]', params: { id: item.id } })} />
          )}
          ListEmptyComponent={
            <View style={styles.center}>
              <Text style={type.heading}>No encontramos conexiones</Text>
              <Text style={[type.caption, { textAlign: 'center' }]}>
                {error && !result
                  ? 'No hay horarios offline descargados y no hay conexión. Conéctate una vez para descargarlos.'
                  : 'Prueba con otra hora o un punto de partida más cercano a una estación.'}
              </Text>
            </View>
          }
        />
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: colors.background },
  header: { backgroundColor: colors.surface, paddingHorizontal: space.lg, paddingBottom: space.lg, gap: space.sm },
  list: { padding: space.md },
  center: { flex: 1, alignItems: 'center', justifyContent: 'center', gap: space.md, padding: space.xl, marginTop: space.xl },
});
