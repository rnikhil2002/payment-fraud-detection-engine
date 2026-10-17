import { describe, expect, it } from 'vitest';
import { addAlert, formatAmount, formatScore, riskLevel, socketUrl } from './format.js';

describe('format helpers', () => {
  it('formats amounts with two decimals', () => {
    expect(formatAmount(1234.5)).toBe('1,234.50');
    expect(formatAmount(null)).toBe('-');
  });

  it('shows n/a when the ML score is missing', () => {
    expect(formatScore(null)).toBe('n/a');
    expect(formatScore(0.8349)).toBe('0.83');
  });

  it('maps risk to the same thresholds as the engine', () => {
    expect(riskLevel(0.2)).toBe('low');
    expect(riskLevel(0.45)).toBe('medium');
    expect(riskLevel(0.75)).toBe('high');
  });

  it('keeps alerts newest first and unique', () => {
    let list = [];
    list = addAlert(list, { txId: 'a' });
    list = addAlert(list, { txId: 'b' });
    list = addAlert(list, { txId: 'a' });
    expect(list.map((a) => a.txId)).toEqual(['a', 'b']);
    expect(addAlert(list, { txId: 'c' }, 2)).toHaveLength(2);
  });

  it('builds the websocket url from the page location', () => {
    expect(socketUrl({ protocol: 'http:', host: 'localhost:3000' })).toBe('ws://localhost:3000/ws/alerts');
    expect(socketUrl({ protocol: 'https:', host: 'fraud.example.com' })).toBe('wss://fraud.example.com/ws/alerts');
  });
});
