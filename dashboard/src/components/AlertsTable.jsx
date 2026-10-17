import { formatAmount, formatTime, riskLevel } from '../format.js';

export default function AlertsTable({ alerts, selectedTx, onSelect }) {
  if (!alerts.length) {
    return <p className="empty">No alerts yet. Start the simulator to stream payments.</p>;
  }
  return (
    <div className="table-wrap">
      <table>
        <thead>
          <tr>
            <th>Time</th>
            <th>Account</th>
            <th>Type</th>
            <th className="num">Amount</th>
            <th className="num">Risk</th>
            <th>Decision</th>
            <th>Top reason</th>
          </tr>
        </thead>
        <tbody>
          {alerts.map((a) => (
            <tr
              key={a.txId}
              className={a.txId === selectedTx ? 'selected' : ''}
              onClick={() => onSelect(a)}
            >
              <td>{formatTime(a.timestamp)}</td>
              <td className="mono">{a.accountId}</td>
              <td>{a.type}</td>
              <td className="num">{formatAmount(a.amount)}</td>
              <td className="num">
                <span className={`pill ${riskLevel(a.riskScore)}`}>{a.riskScore.toFixed(2)}</span>
              </td>
              <td>
                <span className={`decision ${a.decision.toLowerCase()}`}>{a.decision}</span>
              </td>
              <td className="reason">{a.ruleHits[0]?.rule || 'ML_SCORE'}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
