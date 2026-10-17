import { useEffect, useRef, useState } from 'react';
import { addAlert, socketUrl } from './format.js';

/**
 * Loads recent alerts over REST, then keeps them live over the WebSocket.
 * Reconnects with a short back-off if the engine restarts.
 */
export function useAlertStream() {
  const [alerts, setAlerts] = useState([]);
  const [stats, setStats] = useState(null);
  const [connected, setConnected] = useState(false);
  const retry = useRef(0);

  useEffect(() => {
    fetch('/api/alerts?limit=100')
      .then((r) => (r.ok ? r.json() : []))
      .then((initial) => setAlerts((current) => (current.length ? current : initial)))
      .catch(() => {});
    fetch('/api/stats')
      .then((r) => (r.ok ? r.json() : null))
      .then((s) => s && setStats(s))
      .catch(() => {});
  }, []);

  useEffect(() => {
    let socket;
    let timer;
    let closed = false;

    const connect = () => {
      socket = new WebSocket(socketUrl(window.location));
      socket.onopen = () => {
        retry.current = 0;
        setConnected(true);
      };
      socket.onmessage = (event) => {
        const message = JSON.parse(event.data);
        if (message.type === 'alert') setAlerts((current) => addAlert(current, message.data));
        if (message.type === 'stats') setStats(message.data);
      };
      socket.onclose = () => {
        setConnected(false);
        if (closed) return;
        const delay = Math.min(10000, 1000 * 2 ** retry.current);
        retry.current += 1;
        timer = setTimeout(connect, delay);
      };
    };
    connect();

    return () => {
      closed = true;
      clearTimeout(timer);
      if (socket) socket.close();
    };
  }, []);

  return { alerts, stats, connected };
}
