import { useMemo, useRef, useEffect, useState } from 'react';
import ForceGraph2D from 'react-force-graph-2d';

const COLORS = {
  Account: '#4f8cff',
  Device: '#f5a623',
  Merchant: '#2bb673',
  flagged: '#e5484d',
};

export default function NetworkGraph({ graph }) {
  const wrapper = useRef(null);
  const [width, setWidth] = useState(480);

  useEffect(() => {
    if (!wrapper.current) return undefined;
    const observer = new ResizeObserver(([entry]) => setWidth(Math.floor(entry.contentRect.width)));
    observer.observe(wrapper.current);
    return () => observer.disconnect();
  }, []);

  const data = useMemo(() => ({
    nodes: graph.nodes.map((n) => ({ ...n, root: n.name === graph.rootAccount })),
    links: graph.links.map((l) => ({ ...l })),
  }), [graph]);

  return (
    <div ref={wrapper} className="graph">
      <ForceGraph2D
        graphData={data}
        width={width}
        height={360}
        backgroundColor="#0f1420"
        nodeRelSize={5}
        linkColor={(l) => (l.type === 'SENT' ? '#e5a3ff' : '#3a4560')}
        linkDirectionalArrowLength={4}
        linkDirectionalArrowRelPos={1}
        linkLabel={(l) => (l.amount ? `${l.type} ${l.amount.toFixed(2)}` : l.type)}
        nodeLabel={(n) => `${n.label}: ${n.name}${n.flagged ? ' (blocked)' : ''}`}
        nodeCanvasObject={(node, ctx, scale) => {
          const r = node.root ? 7 : 5;
          ctx.beginPath();
          ctx.arc(node.x, node.y, r, 0, 2 * Math.PI);
          ctx.fillStyle = node.flagged ? COLORS.flagged : COLORS[node.label] || '#999';
          ctx.fill();
          if (node.root) {
            ctx.lineWidth = 2 / scale;
            ctx.strokeStyle = '#ffffff';
            ctx.stroke();
          }
          if (scale > 1.6) {
            ctx.font = `${10 / scale}px sans-serif`;
            ctx.fillStyle = '#c9d1e3';
            ctx.fillText(node.name, node.x + r + 2, node.y + 3);
          }
        }}
      />
      <div className="legend">
        <span><i style={{ background: COLORS.Account }} />Account</span>
        <span><i style={{ background: COLORS.Device }} />Device</span>
        <span><i style={{ background: COLORS.Merchant }} />Merchant</span>
        <span><i style={{ background: COLORS.flagged }} />Blocked</span>
      </div>
    </div>
  );
}
