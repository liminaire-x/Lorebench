import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react'
import {
  ReactFlow,
  Background,
  Controls,
  MiniMap,
  Handle,
  Position,
  applyNodeChanges,
  applyEdgeChanges,
} from '@xyflow/react'
import '@xyflow/react/dist/style.css'
import { newId } from './ids.js'
import QuestTab from './QuestTab.jsx'
import { MovesDialog, playersOn, removedStages } from './Stages.jsx'
import NpcTab from './NpcTab.jsx'
import { isCases, lineText } from './Section.jsx'
import { FolderPanel, FolderSelect, FolderTree, addFolder, placeIn } from './FolderTree.jsx'

// Must match the server (GraphFormat.java, NpcFormat.java, QuestFormat.java).
const FORMAT = 1
const NPC_FORMAT = 1
const QUEST_FORMAT = 1
const NEXT = 'next'

const CATEGORY_COLORS = { trigger: '#2a7d4f', condition: '#7c3aed', action: '#2563eb' }
const catColor = (c) => CATEGORY_COLORS[c] || '#555'

// --- saved document (server format) <-> React Flow ---

const edgeId = (from, out) => `${from}:${out}` // one link per way out, so this is unique

function toFlow(g) {
  return placeIn({
    id: g.id,
    name: g.name,
    nodes: g.nodes.map((n) => ({
      id: n.id,
      type: 'lorebench',
      position: { x: n.pos?.[0] ?? 0, y: n.pos?.[1] ?? 0 },
      data: { type: n.type, config: n.config || {} },
    })),
    edges: g.links.map((l) => {
      const out = l.out ?? NEXT
      return { id: edgeId(l.from, out), source: l.from, sourceHandle: out, target: l.to }
    }),
  }, g.folder)
}

function toDoc(graphs, folders) {
  return {
    format: FORMAT,
    folders,
    graphs: graphs.map((g) => placeIn({
      id: g.id,
      name: g.name,
      nodes: g.nodes.map((n) => ({
        id: n.id,
        type: n.data.type,
        config: n.data.config,
        pos: [Math.round(n.position.x), Math.round(n.position.y)],
      })),
      links: g.edges.map((e) => {
        const link = { from: e.source, to: e.target }
        if (e.sourceHandle && e.sourceHandle !== NEXT) link.out = e.sourceHandle
        return link
      }),
    }, g.folder)),
  }
}

// Lines an author left blank are dropped on publish, and so are lists left empty,
// because the server takes only real lines (DialogueLines.java).
const realLines = (lines) => (lines || []).filter((l) => lineText(l).trim() !== '')

// Cases (Speech.java) keep their real lines and filled-in conditions; an Otherwise with
// nothing to say is the same as none, and a lone Otherwise is written as plain lines.
// A case left with no condition is sent as is, and the server says what is missing.
function realSpeech(v) {
  if (!isCases(v)) return realLines(v)
  const cases = v.map((c) => {
    const lines = realLines(c.lines)
    if (!c.when) return { lines }
    const when = {}
    const t = c.when.timesDeclined
    if (typeof t === 'number' || (t && (t.min !== undefined || t.max !== undefined))) when.timesDeclined = t
    const states = Object.entries(c.when.questState || {}).filter(([quest]) => quest)
    if (states.length) when.questState = Object.fromEntries(states)
    const stages = Object.entries(c.when.stage || {}).filter(([quest, stage]) => quest && stage)
    if (stages.length) when.stage = Object.fromEntries(stages)
    return { when, lines }
  })
  if (cases.length && !cases[cases.length - 1].when && !cases[cases.length - 1].lines.length) cases.pop()
  return cases.length === 1 && !cases[0].when ? cases[0].lines : cases
}

function tidyNpc(n) {
  const { greeting, ...rest } = n
  const said = realSpeech(greeting)
  return said.length ? { ...rest, greeting: said } : rest
}

// A quest's lines and each of its stages' lines (0015).
function tidyLines(thing) {
  const { lines, ...rest } = thing
  const kept = Object.fromEntries(Object.entries(lines || {}).map(([k, v]) => [k, realSpeech(v)]).filter(([, v]) => v.length))
  return Object.keys(kept).length ? { ...rest, lines: kept } : rest
}

// A stage: its empty lines, and a "when done" text of only spaces, go.
function tidyStage(st) {
  const { readyText, ...rest } = tidyLines(st)
  return readyText?.trim() ? { ...rest, readyText } : rest
}

function tidyQuest(q) {
  const tidy = tidyLines(q)
  return q.stages ? { ...tidy, stages: q.stages.map(tidyStage) } : tidy
}

// --- node on the canvas ---

const SchemaContext = createContext({})

const handleStyle = { width: 10, height: 10, background: '#e5e7eb', border: '1px solid #4b5563' }

function LorebenchNode({ data, selected }) {
  const def = useContext(SchemaContext)[data.type]
  if (!def) {
    return (
      <div style={{ border: '2px solid #c0392b', borderRadius: 8, background: '#fff', padding: 8, fontSize: 12 }}>
        <Handle type="target" position={Position.Left} style={handleStyle} />
        unknown: {data.type}
      </div>
    )
  }
  const outs = def.outs || []
  return (
    <div
      style={{
        border: `2px solid ${selected ? '#111' : '#c9c9c9'}`,
        borderRadius: 8,
        background: '#fff',
        minWidth: 170,
        fontSize: 12,
        boxShadow: '0 1px 3px rgba(0,0,0,0.12)',
      }}
    >
      <div style={{ padding: '5px 10px', background: catColor(def.category), color: '#fff', borderRadius: '6px 6px 0 0', fontWeight: 600 }}>
        {def.label}
      </div>
      <div style={{ position: 'relative', padding: '4px 10px', minHeight: 18 }}>
        {!def.trigger && <Handle type="target" position={Position.Left} style={handleStyle} />}
        {(def.fields || []).map((f) => (
          <div key={f.id} style={{ color: '#555', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis', maxWidth: 190 }}>
            {f.label}: {data.config?.[f.id] ?? ''}
          </div>
        ))}
      </div>
      {outs.map((out) => (
        <div key={out} style={{ position: 'relative', textAlign: 'right', padding: '2px 14px', fontSize: 10, color: '#374151', fontWeight: 600 }}>
          {outs.length > 1 ? out : ' '}
          <Handle type="source" id={out} position={Position.Right} style={handleStyle} />
        </div>
      ))}
    </div>
  )
}

const nodeTypes = { lorebench: LorebenchNode }

// Header tabs, one screen each.
const TABS = [
  { id: 'graphs', label: 'Graphs' },
  { id: 'quests', label: 'Quests' },
  { id: 'npcs', label: 'NPCs' },
]
const isTab = (id) => TABS.some((t) => t.id === id)

// --- app ---

export default function App() {
  const [schema, setSchema] = useState([])
  const [graphs, setGraphs] = useState([])
  const [currentId, setCurrentId] = useState(null)
  const [selectedNodeId, setSelectedNodeId] = useState(null)
  const [npcs, setNpcs] = useState([]) // [{ id, name }]
  const [placements, setPlacements] = useState({}) // { npcId: [{ dim, x, y, z }] }
  const [quests, setQuests] = useState([]) // server format: [{ id, title, icon?, text?, folder?, stages, rewards }]
  // Each document keeps its own folders, server format: [{ id, name, parent? }]
  const [questFolders, setQuestFolders] = useState([])
  const [npcFolders, setNpcFolders] = useState([])
  const [graphFolders, setGraphFolders] = useState([])
  const [graphFolderId, setGraphFolderId] = useState(null) // a folder chosen in the graph tree
  const [graphCollapsed, setGraphCollapsed] = useState(() => new Set())
  const [tab, setTab] = useState(() => {
    try { const t = localStorage.getItem('lorebench.tab'); return isTab(t) ? t : 'graphs' } catch (e) { return 'graphs' }
  })
  const [status, setStatus] = useState('connecting...')
  const [message, setMessage] = useState(null) // { ok, text }
  const [publishing, setPublishing] = useState(false)
  // The quests as last published: a stage gone from them since may have players to move (0015).
  const [publishedQuests, setPublishedQuests] = useState([])
  const [moveAsks, setMoveAsks] = useState(null) // removed stages with players, while asking where they go

  const byType = useMemo(() => Object.fromEntries(schema.map((d) => [d.type, d])), [schema])
  const current = graphs.find((g) => g.id === currentId) || null

  const pickTab = useCallback((id) => {
    setTab(id)
    try { localStorage.setItem('lorebench.tab', id) } catch (e) { /* not remembered */ }
  }, [])

  const loadPlacements = useCallback(async () => {
    try {
      setPlacements(await fetch('/api/npc-placements').then((r) => r.json()))
    } catch (e) { /* shown as offline elsewhere */ }
  }, [])

  useEffect(() => {
    let cancelled = false
    async function boot() {
      try {
        const s = await fetch('/api/schema').then((r) => r.json())
        const doc = await fetch('/api/graphs').then((r) => r.json())
        const npcDoc = await fetch('/api/npcs').then((r) => r.json())
        const questDoc = await fetch('/api/quests').then((r) => r.json())
        if (cancelled) return
        setSchema(s.nodes || [])
        const loaded = (doc.graphs || []).map(toFlow)
        setGraphs(loaded)
        setCurrentId(loaded[0]?.id ?? null)
        setGraphFolders(doc.folders || [])
        setNpcFolders(npcDoc.folders || [])
        setNpcs(npcDoc.npcs || [])
        setQuests(questDoc.quests || [])
        setPublishedQuests(questDoc.quests || [])
        setQuestFolders(questDoc.folders || [])
        setStatus('ok')
        loadPlacements()
      } catch (e) {
        if (!cancelled) setStatus('offline')
      }
    }
    boot()
    return () => { cancelled = true }
  }, [loadPlacements])


  const updateCurrent = useCallback(
    (fn) => setGraphs((gs) => gs.map((g) => (g.id === currentId ? fn(g) : g))),
    [currentId],
  )

  const onNodesChange = useCallback(
    (changes) => updateCurrent((g) => ({ ...g, nodes: applyNodeChanges(changes, g.nodes) })),
    [updateCurrent],
  )
  const onEdgesChange = useCallback(
    (changes) => updateCurrent((g) => ({ ...g, edges: applyEdgeChanges(changes, g.edges) })),
    [updateCurrent],
  )

  // A way out leads to one node: connecting it again replaces the old link.
  const onConnect = useCallback((c) => {
    const out = c.sourceHandle || NEXT
    const id = edgeId(c.source, out)
    updateCurrent((g) => ({
      ...g,
      edges: g.edges.filter((e) => e.id !== id).concat({ id, source: c.source, sourceHandle: out, target: c.target }),
    }))
  }, [updateCurrent])

  const isValidConnection = useCallback((c) => {
    if (!current || c.source === c.target) return false
    const target = current.nodes.find((n) => n.id === c.target)
    const def = target && byType[target.data.type]
    return !!def && !def.trigger
  }, [current, byType])

  const addNode = useCallback((def) => {
    if (!current) return
    const id = newId('node', current.nodes.map((n) => n.id))
    const config = Object.fromEntries((def.fields || []).map((f) => [f.id, f.default ?? '']))
    const node = {
      id,
      type: 'lorebench',
      position: { x: 150 + Math.random() * 150, y: 100 + Math.random() * 150 },
      data: { type: def.type, config },
    }
    updateCurrent((g) => ({ ...g, nodes: g.nodes.concat(node) }))
    setSelectedNodeId(id)
  }, [current, updateCurrent])

  const selectedNode = current?.nodes.find((n) => n.id === selectedNodeId) || null
  const selectedDef = selectedNode ? byType[selectedNode.data.type] : null

  const setConfig = useCallback((key, value) => {
    updateCurrent((g) => ({
      ...g,
      nodes: g.nodes.map((n) => (n.id === selectedNodeId
        ? { ...n, data: { ...n.data, config: { ...n.data.config, [key]: value } } }
        : n)),
    }))
  }, [selectedNodeId, updateCurrent])

  const deleteNode = useCallback(() => {
    updateCurrent((g) => ({
      ...g,
      nodes: g.nodes.filter((n) => n.id !== selectedNodeId),
      edges: g.edges.filter((e) => e.source !== selectedNodeId && e.target !== selectedNodeId),
    }))
    setSelectedNodeId(null)
  }, [selectedNodeId, updateCurrent])

  const graphFolder = graphFolders.find((f) => f.id === graphFolderId) || null
  // Where "+ Folder" and "+ Graph" put the new thing: the chosen folder, or the open graph's folder.
  const graphTarget = graphFolder?.id ?? current?.folder ?? ''

  const openGraph = useCallback((id) => { setCurrentId(id); setGraphFolderId(null); setSelectedNodeId(null) }, [])
  const chooseGraphFolder = useCallback((id) => { setGraphFolderId(id); setSelectedNodeId(null) }, [])

  const newGraph = useCallback(() => {
    const name = window.prompt('Graph name:')
    if (name == null || !name.trim()) return
    const id = newId('graph', graphs.map((g) => g.id))
    setGraphs((gs) => gs.concat(placeIn({ id, name: name.trim(), nodes: [], edges: [] }, graphTarget)))
    openGraph(id)
  }, [graphs, graphTarget, openGraph])

  const newGraphFolder = useCallback(() => {
    const id = addFolder(graphFolders, setGraphFolders, graphTarget)
    if (id) chooseGraphFolder(id)
  }, [graphFolders, graphTarget, chooseGraphFolder])

  const deleteGraph = useCallback(() => {
    if (!current || !window.confirm(`Delete graph '${current.name}'? (takes effect on publish)`)) return
    const rest = graphs.filter((g) => g.id !== current.id)
    setGraphs(rest)
    setCurrentId(rest[0]?.id ?? null)
    setSelectedNodeId(null)
  }, [current, graphs])

  // `moves`: a removed stage's id → the stage its players go to (0015).
  const publish = useCallback(async (moves = {}) => {
    setPublishing(true)
    setMessage(null)
    try {
      const sent = quests.map(tidyQuest)
      const res = await fetch('/api/publish', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          graphs: toDoc(graphs, graphFolders),
          npcs: { format: NPC_FORMAT, folders: npcFolders, npcs: npcs.map(tidyNpc) },
          quests: { format: QUEST_FORMAT, folders: questFolders, quests: sent },
          ...(Object.keys(moves).length ? { moves } : {}),
        }),
      })
      const data = await res.json()
      setMessage(data.accepted
        ? { ok: true, text: 'Published.' }
        : { ok: false, text: (data.errors || ['unknown error']).join('\n') })
      if (data.accepted) {
        setPublishedQuests(sent)
        loadPlacements()
      }
    } catch (e) {
      setMessage({ ok: false, text: 'Publish error: ' + e })
    } finally {
      setPublishing(false)
    }
  }, [graphs, npcs, quests, graphFolders, npcFolders, questFolders, loadPlacements])

  // Publish, asking first where the players on removed stages go (0015).
  const startPublish = useCallback(async () => {
    const removed = removedStages(publishedQuests, quests)
    const counts = await Promise.all(removed.map((r) => playersOn(r.quest.id, r.stage.id)))
    const asks = removed.map((r, i) => ({ ...r, count: counts[i] })).filter((a) => a.count !== 0)
    if (asks.length) setMoveAsks(asks)
    else publish()
  }, [publishedQuests, quests, publish])

  const palette = useMemo(() => {
    const g = {}
    schema.forEach((d) => { (g[d.category] = g[d.category] || []).push(d) })
    return g
  }, [schema])

  const button = { display: 'block', width: '100%', textAlign: 'left', padding: '5px 8px', marginBottom: 3, cursor: 'pointer', border: '1px solid #ddd', borderRadius: 5, background: '#fafafa' }
  const sectionTitle = { fontWeight: 600, margin: '4px 0 6px' }
  const toolButton = { padding: '4px 8px', cursor: 'pointer', color: '#2563eb', border: '1px solid #ddd', borderRadius: 5, background: '#fafafa' }

  return (
    <SchemaContext.Provider value={byType}>
      <div style={{ height: '100%', display: 'flex', flexDirection: 'column' }}>
        <header style={{ display: 'flex', alignItems: 'center', gap: 12, padding: '10px 16px', borderBottom: '1px solid #ddd' }}>
          <strong style={{ fontSize: 18 }}>Lorebench</strong>
          <nav role="tablist" style={{ display: 'flex', gap: 2, alignSelf: 'stretch', margin: '-10px 0' }}>
            {TABS.map((t) => {
              const active = t.id === tab
              return (
                <button
                  key={t.id}
                  role="tab"
                  aria-selected={active}
                  onClick={() => pickTab(t.id)}
                  style={{
                    padding: '0 14px', border: 'none', background: 'none', fontSize: 13,
                    borderBottom: `2px solid ${active ? '#2563eb' : 'transparent'}`,
                    color: active ? '#111' : '#555',
                    fontWeight: active ? 600 : 400,
                    cursor: 'pointer',
                  }}
                >
                  {t.label}
                </button>
              )
            })}
          </nav>
          <span style={{ fontSize: 13, color: status === 'ok' ? '#2a7d4f' : '#c0392b' }}>server: {status}</span>
          <span style={{ flex: 1 }} />
          <button onClick={startPublish} disabled={publishing || status !== 'ok'} style={{ padding: '6px 14px', cursor: 'pointer' }}>
            {publishing ? 'Publishing...' : 'Publish'}
          </button>
        </header>
        {message && (
          <div
            onClick={() => setMessage(null)}
            style={{ padding: '6px 16px', fontSize: 12, whiteSpace: 'pre-wrap', cursor: 'pointer',
              background: message.ok ? '#ecfdf5' : '#fef2f2', color: message.ok ? '#065f46' : '#991b1b' }}
          >
            {message.text}
          </div>
        )}

        {moveAsks && (
          <MovesDialog
            asks={moveAsks} quests={quests} onCancel={() => setMoveAsks(null)}
            onPublish={(moves) => { setMoveAsks(null); publish(moves) }}
          />
        )}
        <QuestTab
          quests={quests} setQuests={setQuests} folders={questFolders} setFolders={setQuestFolders} npcs={npcs}
          status={status} setMessage={setMessage} hidden={tab !== 'quests'}
        />
        <NpcTab
          npcs={npcs} setNpcs={setNpcs} folders={npcFolders} setFolders={setNpcFolders}
          placements={placements} quests={quests} status={status} hidden={tab !== 'npcs'}
        />

        {tab === 'graphs' && <div style={{ flex: 1, minHeight: 0, display: 'flex' }}>
          <aside style={{ width: 240, borderRight: '1px solid #ddd', display: 'flex', flexDirection: 'column', fontSize: 12 }}>
            <div style={{ display: 'flex', gap: 6, padding: 10, borderBottom: '1px solid #eee' }}>
              <button onClick={newGraphFolder} disabled={status !== 'ok'} style={toolButton}>+ Folder</button>
              <button onClick={newGraph} disabled={status !== 'ok'} style={toolButton}>+ Graph</button>
            </div>
            <div style={{ flex: 1, minHeight: 0, overflowY: 'auto', padding: 6 }}>
              {graphFolders.length === 0 && graphs.length === 0
                ? <div style={{ color: '#888', fontSize: 11, padding: 6 }}>No graphs yet. Create one with "+ Graph".</div>
                : (
                  <FolderTree
                    folders={graphFolders}
                    selected={graphFolderId ? { kind: 'folder', id: graphFolderId } : { kind: 'item', id: currentId }}
                    onSelect={(sel) => (sel.kind === 'folder' ? chooseGraphFolder(sel.id) : openGraph(sel.id))}
                    collapsed={graphCollapsed} setCollapsed={setGraphCollapsed}
                    items={graphs.map((g) => ({ id: g.id, label: g.name, folder: g.folder }))}
                  />
                )}
            </div>
            <div style={{ flex: 1, minHeight: 0, overflowY: 'auto', padding: 10, borderTop: '1px solid #ddd' }}>
              <div style={sectionTitle}>Nodes</div>
              {!current && <div style={{ color: '#888', fontSize: 11 }}>Open a graph to add nodes.</div>}
              {current && Object.entries(palette).map(([cat, defs]) => (
                <div key={cat} style={{ marginBottom: 10 }}>
                  <div style={{ textTransform: 'uppercase', fontSize: 10, color: catColor(cat), fontWeight: 700, marginBottom: 4 }}>{cat}</div>
                  {defs.map((d) => (
                    <button key={d.type} onClick={() => addNode(d)} style={button}>+ {d.label}</button>
                  ))}
                </div>
              ))}
            </div>
          </aside>

          <div style={{ flex: 1, minWidth: 0 }}>
            {current ? (
              <ReactFlow
                key={current.id}
                nodes={current.nodes}
                edges={current.edges}
                nodeTypes={nodeTypes}
                onNodesChange={onNodesChange}
                onEdgesChange={onEdgesChange}
                onConnect={onConnect}
                isValidConnection={isValidConnection}
                onNodeClick={(_, n) => setSelectedNodeId(n.id)}
                onPaneClick={() => setSelectedNodeId(null)}
                fitView
              >
                <Background />
                <Controls />
                <MiniMap />
              </ReactFlow>
            ) : (
              <div style={{ padding: 24, color: '#888' }}>No graph open. Choose one on the left, or create one with "+ Graph".</div>
            )}
          </div>

          <aside style={{ width: 300, borderLeft: '1px solid #ddd', overflowY: 'auto', padding: 12, fontSize: 12 }}>
            {selectedNode ? (
              <>
                <div style={{ fontWeight: 600 }}>{selectedDef?.label || selectedNode.data.type}</div>
                <div style={{ color: '#888', marginBottom: 10 }}>{selectedNode.data.type} · {selectedNode.id}</div>
                {(selectedDef?.fields || []).map((f) => (
                  <label key={f.id} style={{ display: 'block', marginBottom: 10 }}>
                    <div style={{ marginBottom: 3 }}>{f.label}</div>
                    {f.kind === 'npc' ? (
                      <select
                        value={selectedNode.data.config?.[f.id] ?? ''}
                        onChange={(e) => setConfig(f.id, e.target.value)}
                        style={{ width: '100%', padding: '4px 6px', boxSizing: 'border-box' }}
                      >
                        <option value="">(choose an NPC)</option>
                        {npcs.map((n) => <option key={n.id} value={n.id}>{n.name} ({n.id})</option>)}
                      </select>
                    ) : f.kind === 'quest' ? (
                      <select
                        value={selectedNode.data.config?.[f.id] ?? ''}
                        onChange={(e) => setConfig(f.id, e.target.value)}
                        style={{ width: '100%', padding: '4px 6px', boxSizing: 'border-box' }}
                      >
                        <option value="">(choose a quest)</option>
                        {quests.map((q) => <option key={q.id} value={q.id}>{q.title} ({q.id})</option>)}
                      </select>
                    ) : (
                      <input
                        value={selectedNode.data.config?.[f.id] ?? ''}
                        onChange={(e) => setConfig(f.id, e.target.value)}
                        style={{ width: '100%', padding: '4px 6px', boxSizing: 'border-box' }}
                      />
                    )}
                  </label>
                ))}
                <button onClick={deleteNode} style={{ padding: '5px 10px', cursor: 'pointer', color: '#c0392b' }}>Delete node</button>
              </>
            ) : graphFolder ? (
              <FolderPanel
                folder={graphFolder} folders={graphFolders} setFolders={setGraphFolders} items={graphs} setItems={setGraphs}
                onDeleted={(up) => setGraphFolderId(up || null)}
              />
            ) : current ? (
              <>
                <div style={{ fontWeight: 600, marginBottom: 8 }}>Graph</div>
                <label style={{ display: 'block', marginBottom: 10 }}>
                  <div style={{ marginBottom: 3 }}>Name</div>
                  <input
                    value={current.name}
                    onChange={(e) => updateCurrent((g) => ({ ...g, name: e.target.value }))}
                    style={{ width: '100%', padding: '4px 6px', boxSizing: 'border-box' }}
                  />
                </label>
                <div style={{ color: '#888', marginBottom: 12 }}>id: {current.id} (fixed)</div>
                <label style={{ display: 'block', marginBottom: 12 }}>
                  <div style={{ marginBottom: 3 }}>Folder</div>
                  <FolderSelect folders={graphFolders} value={current.folder} onChange={(v) => updateCurrent((g) => placeIn(g, v))} />
                </label>
                <button onClick={deleteGraph} style={{ padding: '5px 10px', cursor: 'pointer', color: '#c0392b' }}>Delete graph</button>
              </>
            ) : null}
          </aside>
        </div>}
      </div>
    </SchemaContext.Provider>
  )
}
