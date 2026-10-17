export default function StatsBar({ stats }) {
  const s = stats || { total: 0, allowed: 0, review: 0, blocked: 0, avgLatencyMs: 0, maxLatencyMs: 0 };
  const alertRate = s.total ? (((s.review + s.blocked) / s.total) * 100).toFixed(1) : '0.0';
  const cards = [
    { label: 'Payments scored', value: s.total.toLocaleString() },
    { label: 'Allowed', value: s.allowed.toLocaleString() },
    { label: 'Review', value: s.review.toLocaleString(), tone: 'medium' },
    { label: 'Blocked', value: s.blocked.toLocaleString(), tone: 'high' },
    { label: 'Alert rate', value: `${alertRate}%` },
    { label: 'Avg latency', value: `${s.avgLatencyMs} ms` },
  ];
  return (
    <div className="stats">
      {cards.map((c) => (
        <div key={c.label} className={`stat ${c.tone || ''}`}>
          <div className="stat-value">{c.value}</div>
          <div className="stat-label">{c.label}</div>
        </div>
      ))}
    </div>
  );
}
