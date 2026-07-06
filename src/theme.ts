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

export type FilterId =
  | 'smooth'
  | 'glow'
  | 'clarity'
  | 'warm'
  | 'sharp'
  | 'eyes'
  | 'nose'
  | 'slim';

export const BEAUTY_FILTERS: {
  id: FilterId;
  label: string;
  icon: string;
  /** native prop name driven by this filter's intensity */
  prop:
    | 'smoothing'
    | 'glow'
    | 'clarity'
    | 'warmth'
    | 'sharpness'
    | 'eyeEnlarge'
    | 'noseSlim'
    | 'faceSlim';
}[] = [
  { id: 'smooth', label: 'Smooth', icon: '💧', prop: 'smoothing' },
  { id: 'glow', label: 'Glow', icon: '✨', prop: 'glow' },
  { id: 'clarity', label: 'Clear', icon: '🫧', prop: 'clarity' },
  { id: 'warm', label: 'Warm', icon: '🔆', prop: 'warmth' },
  { id: 'sharp', label: 'Sharp', icon: '💎', prop: 'sharpness' },
  { id: 'eyes', label: 'Big Eyes', icon: '👁️', prop: 'eyeEnlarge' },
  { id: 'nose', label: 'Nose', icon: '👃', prop: 'noseSlim' },
  { id: 'slim', label: 'Slim', icon: '🫰', prop: 'faceSlim' },
];

// A polished, flattering look out of the box: clear even skin, soft smoothing,
// a gentle skin-tone glow and a touch of warmth. Eye-enlarge stays off by
// default (it's a strong geometric effect users can dial in themselves).
export const DEFAULT_INTENSITIES: Record<FilterId, number> = {
  smooth: 0.75,
  glow: 0.4,
  clarity: 0.55,
  warm: 0.08,
  // Lower default sharp: it drives the micro-contrast, shadow toe and sharpen,
  // so a high default was the main reason the look read as an OBVIOUS filter
  // (higher contrast, more noise, harsher beard) vs the natural reference.
  sharp: 0.2,
  // Stronger default reshape: the target has a SLIGHT V-line / bigger eyes /
  // slimmer nose that ours barely showed. Still subtle, still user-dialable.
  eyes: 0.22,
  nose: 0.3,
  slim: 0.55,
};
