// Shared design tokens so every screen/component reads from one system.
export const theme = {
  color: {
    bg: '#08080C',
    bgElev: '#14141C',
    text: '#FFFFFF',
    textMuted: '#A2A8B4',
    textDim: '#6B7280',
    accent: '#FF375F',
    accentSoft: 'rgba(255,55,95,0.18)',
    accentGlow: 'rgba(255,55,95,0.45)',
    mint: '#15FF8C',
    mintSoft: 'rgba(21,255,140,0.16)',
    glass: 'rgba(16,16,24,0.55)',
    glassStrong: 'rgba(12,12,18,0.72)',
    glassBorder: 'rgba(255,255,255,0.14)',
    hairline: 'rgba(255,255,255,0.09)',
    track: 'rgba(255,255,255,0.18)',
  },
  radius: { sm: 12, md: 18, lg: 26, pill: 999 },
  space: (n: number) => n * 4,
  font: {
    hero: 42,
    title: 22,
    body: 15,
    label: 13,
    tiny: 11,
  },
} as const;

export type FilterId = 'smooth' | 'glow' | 'clarity' | 'warm' | 'eyes';

export const BEAUTY_FILTERS: {
  id: FilterId;
  label: string;
  icon: string;
  /** native prop name driven by this filter's intensity */
  prop: 'smoothing' | 'glow' | 'clarity' | 'warmth' | 'eyeEnlarge';
}[] = [
  { id: 'smooth', label: 'Smooth', icon: '💧', prop: 'smoothing' },
  { id: 'glow', label: 'Glow', icon: '✨', prop: 'glow' },
  { id: 'clarity', label: 'Clear', icon: '🫧', prop: 'clarity' },
  { id: 'warm', label: 'Warm', icon: '🔆', prop: 'warmth' },
  { id: 'eyes', label: 'Big Eyes', icon: '👁️', prop: 'eyeEnlarge' },
];

export const DEFAULT_INTENSITIES: Record<FilterId, number> = {
  smooth: 0.62,
  glow: 0.4,
  clarity: 0.55,
  warm: 0.2,
  eyes: 0,
};
