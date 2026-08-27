/** Two-letter avatar initials from the best available identity field. */
export function initials(name?: string | null, email?: string | null, fallback = 'U'): string {
  const raw = (name || email || '').trim();
  if (!raw) return fallback;
  // For an email, use only the local part (before @) so we don't cross the @ boundary.
  const base = raw.includes('@') ? raw.split('@')[0] : raw;
  const words = base.split(/[\s._-]+/).filter(Boolean);
  if (words.length >= 2) return (words[0][0] + words[1][0]).toUpperCase();
  return base.slice(0, 2).toUpperCase();
}

/** Agent 1 tier weights (as percentages) must sum to 100% before a profile save is allowed. */
export function weightsSumTo100(pcts: number[]): boolean {
  const total = pcts.reduce((a, b) => a + b, 0);
  return Math.abs(total - 100) < 0.01;
}
