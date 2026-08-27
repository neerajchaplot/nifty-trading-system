import { initials, weightsSumTo100 } from './profile.util';

describe('initials', () => {
  it('uses first letters of a two-part display name', () => {
    expect(initials('Neeraj Chaplot', 'x@y.com')).toBe('NC');
  });

  it('derives from email when no name', () => {
    expect(initials(null, 'geekuno@gmail.com')).toBe('GE');
  });

  it('falls back when nothing is available', () => {
    expect(initials(null, null)).toBe('U');
  });
});

describe('weightsSumTo100', () => {
  it('accepts the default 30/20/30/10/10 split', () => {
    expect(weightsSumTo100([30, 20, 30, 10, 10])).toBeTrue();
  });

  it('rejects a split that does not total 100', () => {
    expect(weightsSumTo100([30, 20, 30, 10, 5])).toBeFalse();
  });
});
