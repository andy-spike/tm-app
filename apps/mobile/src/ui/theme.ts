export const colors = {
  brand: '#C8102E',
  brandPressed: '#A00D25',
  text: '#16181D',
  textMuted: '#5B6270',
  textFaint: '#8B92A0',
  surface: '#FFFFFF',
  background: '#F3F4F6',
  border: '#E3E5EA',
  walk: '#8B92A0',
  online: '#1F8A4C',
  offline: '#B26B00',
  offlineSurface: '#FFF4E0',
  danger: '#B42318',
};

export const space = { xs: 4, sm: 8, md: 12, lg: 16, xl: 24 };
export const radius = { sm: 6, md: 10, lg: 16 };

export const type = {
  title: { fontSize: 22, fontWeight: '700' as const, color: colors.text },
  heading: { fontSize: 17, fontWeight: '600' as const, color: colors.text },
  body: { fontSize: 15, color: colors.text },
  caption: { fontSize: 13, color: colors.textMuted },
  time: { fontSize: 20, fontWeight: '700' as const, color: colors.text, fontVariant: ['tabular-nums' as const] },
};
