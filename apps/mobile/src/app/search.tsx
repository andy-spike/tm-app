import type { Place } from '@tm/core';
import * as Location from 'expo-location';
import { router, useLocalSearchParams } from 'expo-router';
import { useState } from 'react';
import { ActivityIndicator, FlatList, Pressable, StyleSheet, Text, TextInput, View } from 'react-native';

import { usePlaceSearch } from '@/data';
import { useTripForm, type Field } from '@/state/trip-form';
import { colors, radius, space, type } from '@/ui/theme';

const kindLabel: Record<Place['kind'], string> = {
  station: 'Estación',
  stop: 'Parada',
  street: 'Calle',
  poi: 'Lugar',
  address: 'Dirección',
  coordinate: 'Ubicación',
};

export default function SearchScreen() {
  const { field } = useLocalSearchParams<{ field: Field }>();
  const { setPlace } = useTripForm();
  const [text, setText] = useState('');
  const { places, isLoading, isUpgrading, error } = usePlaceSearch(text);

  const choose = (place: Place) => {
    setPlace(field ?? 'to', place);
    router.back();
  };

  const useMyLocation = async () => {
    const { granted } = await Location.requestForegroundPermissionsAsync();
    if (!granted) return;
    const pos = await Location.getCurrentPositionAsync({ accuracy: Location.Accuracy.Balanced });
    choose({ id: 'current-location', name: 'Mi ubicación', kind: 'coordinate', lat: pos.coords.latitude, lon: pos.coords.longitude });
  };

  return (
    <View style={styles.container}>
      <View style={styles.inputWrap}>
        <TextInput
          autoFocus
          value={text}
          onChangeText={setText}
          placeholder={field === 'from' ? 'Origen: estación, parada o dirección' : 'Destino: estación, parada o dirección'}
          placeholderTextColor={colors.textFaint}
          style={styles.input}
          returnKeyType="search"
          autoCorrect={false}
        />
        {(isLoading || isUpgrading) && <ActivityIndicator color={colors.textMuted} />}
      </View>

      <FlatList
        data={places}
        keyExtractor={(p) => p.id}
        keyboardShouldPersistTaps="handled"
        ListHeaderComponent={
          <Pressable style={({ pressed }) => [styles.row, pressed && styles.pressed]} onPress={useMyLocation}>
            <Text style={[styles.icon, { color: colors.brand }]}>◎</Text>
            <Text style={[type.body, { fontWeight: '600' }]}>Mi ubicación</Text>
          </Pressable>
        }
        ListEmptyComponent={
          text.trim() && !isLoading && !isUpgrading ? (
            <Text style={styles.empty}>{error ? 'Sin resultados sin conexión. Prueba con el nombre de una estación.' : 'Sin resultados'}</Text>
          ) : undefined
        }
        ItemSeparatorComponent={() => <View style={styles.separator} />}
        renderItem={({ item }) => (
          <Pressable style={({ pressed }) => [styles.row, pressed && styles.pressed]} onPress={() => choose(item)}>
            <Text style={styles.icon}>{item.kind === 'station' || item.kind === 'stop' ? '🚏' : '📍'}</Text>
            <View style={{ flex: 1 }}>
              <Text style={type.body} numberOfLines={1}>
                {item.name}
              </Text>
              <Text style={type.caption}>{kindLabel[item.kind]}</Text>
            </View>
          </Pressable>
        )}
      />
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: colors.surface },
  inputWrap: {
    flexDirection: 'row',
    alignItems: 'center',
    margin: space.lg,
    paddingHorizontal: space.md,
    borderRadius: radius.md,
    backgroundColor: colors.background,
    gap: space.sm,
  },
  input: { flex: 1, fontSize: 16, paddingVertical: space.md, color: colors.text },
  row: { flexDirection: 'row', alignItems: 'center', gap: space.md, paddingHorizontal: space.lg, paddingVertical: space.md },
  pressed: { backgroundColor: colors.background },
  icon: { width: 24, fontSize: 18, textAlign: 'center' },
  separator: { height: 1, backgroundColor: colors.border, marginLeft: 52 },
  empty: { ...type.caption, padding: space.xl, textAlign: 'center' },
});
