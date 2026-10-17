import { useEffect, useState } from 'react';
import { formatAmount, formatScore } from '../format.js';
import NetworkGraph from './NetworkGraph.jsx';

export default function AlertDetail({ alert }) {
  const [graph, setGraph] = useState(null);
  const [error, setError] = useState(null);

  useEffect(() => {
    setGraph(null);
    setError(null);
    fetch(`/api/graph/accounts/${encodeURIComponent(alert.accountId)}`)
      .then((r) => (r.ok ? r.json() : Promise.reject(new Error(`HTTP ${r.status}`))))
      .then(setGraph)
      .catch((e) => setError(e.message));
  }, [alert.accountId]);

  const s = alert.signals || {};
  return (
    <div>
      <div className="panel-head">
        <h2>
          {alert.decision} <span className="mono small">{alert.txId}</span>
        </h2>
      </div>

      <div className="scores">
        <div><span>Risk</span><strong>{formatScore(alert.riskScore)}</strong></div>
        <div><span>Graph</span><strong>{formatScore(alert.graphScore)}</strong></div>
        <div><span>ML model</span><strong>{formatScore(alert.mlScore)}</strong></div>
        <div><span>Amount</span><strong>{formatAmount(alert.amount)}</strong></div>
      </div>

      <h3>Why it fired</h3>
      <ul className="reasons">
        {alert.ruleHits.length ? (
          alert.ruleHits.map((h) => (
            <li key={h.rule}>
              <span className="rule">{h.rule}</span> +{h.weight.toFixed(2)} &middot; {h.reason}
            </li>
          ))
        ) : (
          <li>No graph rules fired; flagged by the ML risk score.</li>
        )}
      </ul>

      <h3>Graph signals</h3>
      <div className="signals">
        <span>accounts on device <b>{s.accountsOnDevice}</b></span>
        <span>device ring <b>{s.deviceRingSize}</b></span>
        <span>tx in 10m <b>{s.txLast10m}</b></span>
        <span>mule chain depth <b>{s.muleChainDepth}</b></span>
        <span>blocked neighbours <b>{s.flaggedNeighbors}</b></span>
        <span>new device <b>{s.newDevice ? 'yes' : 'no'}</b></span>
      </div>

      <h3>Account network</h3>
      {error && <p className="empty">Could not load graph: {error}</p>}
      {!error && !graph && <p className="empty">Loading graph...</p>}
      {graph && <NetworkGraph graph={graph} />}
    </div>
  );
}
