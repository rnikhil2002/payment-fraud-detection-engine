import { useState } from 'react';
import AlertsTable from './components/AlertsTable.jsx';
import AlertDetail from './components/AlertDetail.jsx';
import StatsBar from './components/StatsBar.jsx';
import { useAlertStream } from './useAlertStream.js';

export default function App() {
  const { alerts, stats, connected } = useAlertStream();
  const [selected, setSelected] = useState(null);
  const [filter, setFilter] = useState('ALL');

  const visible = filter === 'ALL' ? alerts : alerts.filter((a) => a.decision === filter);

  return (
    <div className="app">
      <header>
        <h1>Payment Fraud Monitor</h1>
        <span className={`status ${connected ? 'live' : 'down'}`}>
          {connected ? 'Live' : 'Reconnecting...'}
        </span>
      </header>

      <StatsBar stats={stats} />

      <main>
        <section className="panel alerts">
          <div className="panel-head">
            <h2>Alerts</h2>
            <div className="filters">
              {['ALL', 'BLOCK', 'REVIEW'].map((f) => (
                <button key={f} className={filter === f ? 'active' : ''} onClick={() => setFilter(f)}>
                  {f}
                </button>
              ))}
            </div>
          </div>
          <AlertsTable alerts={visible} selectedTx={selected?.txId} onSelect={setSelected} />
        </section>

        <section className="panel detail">
          {selected ? (
            <AlertDetail alert={selected} />
          ) : (
            <p className="empty">Select an alert to see why it fired and the account's network.</p>
          )}
        </section>
      </main>
    </div>
  );
}
