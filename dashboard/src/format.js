export function formatAmount(amount) {
  if (amount === null || amount === undefined || Number.isNaN(Number(amount))) return '-';
  return Number(amount).toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}

export function formatTime(epochMillis) {
  if (!epochMillis) return '-';
  return new Date(epochMillis).toLocaleTimeString('en-US', { hour12: false });
}

export function formatScore(score) {
  if (score === null || score === undefined) return 'n/a';
  return Number(score).toFixed(2);
}

export function riskLevel(score) {
  if (score >= 0.75) return 'high';
  if (score >= 0.45) return 'medium';
  return 'low';
}

/** Merge a new alert into the list, newest first, without duplicates and capped in size. */
export function addAlert(alerts, alert, max = 200) {
  const rest = alerts.filter((a) => a.txId !== alert.txId);
  return [alert, ...rest].slice(0, max);
}

export function socketUrl(location) {
  const scheme = location.protocol === 'https:' ? 'wss' : 'ws';
  return `${scheme}://${location.host}/ws/alerts`;
}
