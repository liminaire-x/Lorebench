import { useState } from 'react'
import { newId } from './ids.js'
import { FolderPanel, FolderSelect, FolderTree, addFolder, folderPath, placeIn } from './FolderTree.jsx'
import { Section, SpeechEditor } from './Section.jsx'

const input = { width: '100%', padding: '4px 6px', boxSizing: 'border-box' }
const label = { display: 'block', marginBottom: 10 }
const hint = { color: '#888', fontSize: 11 }
const toolButton = { padding: '4px 8px', cursor: 'pointer', color: '#2563eb', border: '1px solid #ddd', borderRadius: 5, background: '#fafafa' }

// The NPC tab: a folder tree of NPCs on the left, the chosen NPC's sections (or folder) on the right.
export default function NpcTab({ npcs, setNpcs, folders, setFolders, placements, quests, status, hidden }) {
  const [selected, setSelected] = useState(null) // { kind: 'item' | 'folder', id }
  const [collapsed, setCollapsed] = useState(() => new Set()) // folder ids
  const npc = selected?.kind === 'item' ? npcs.find((n) => n.id === selected.id) || null : null
  const folder = selected?.kind === 'folder' ? folders.find((f) => f.id === selected.id) || null : null
  const placed = npc ? placements[npc.id] || [] : []

  // Where "+ Folder" and "+ NPC" put the new thing: the chosen folder, or the chosen NPC's folder.
  const target = folder?.id ?? npc?.folder ?? ''

  const newFolder = () => {
    const id = addFolder(folders, setFolders, target)
    if (id) setSelected({ kind: 'folder', id })
  }

  const newNpc = () => {
    const name = window.prompt('NPC name (shown above the NPC in game):')
    if (name == null || !name.trim()) return
    const id = newId('npc', npcs.map((n) => n.id))
    setNpcs((ns) => ns.concat(placeIn({ id, name: name.trim() }, target)))
    setSelected({ kind: 'item', id })
  }

  // Edit one field of the chosen NPC; an emptied optional field is dropped.
  const setField = (key, value) => {
    setNpcs((ns) => ns.map((n) => {
      if (n.id !== npc.id) return n
      const next = { ...n, [key]: value }
      if (key !== 'name' && value.trim() === '') delete next[key]
      return next
    }))
  }

  // Edit one animation of the talk set; an emptied one is dropped, and so is an empty set.
  const setTalk = (key, value) => {
    setNpcs((ns) => ns.map((n) => {
      if (n.id !== npc.id) return n
      const { talk, ...rest } = n
      const next = { ...talk, [key]: value }
      if (value.trim() === '') delete next[key]
      return Object.keys(next).length ? { ...rest, talk: next } : rest
    }))
  }

  // The voice (0014): a sound name, or { sound, pitch } when the pitch isn't 1. No sound = none.
  const voiceSound = typeof npc?.voice === 'string' ? npc.voice : npc?.voice?.sound ?? ''
  const voicePitch = typeof npc?.voice === 'object' && npc.voice ? npc.voice.pitch : ''
  const setVoice = (sound, pitch) => {
    setNpcs((ns) => ns.map((n) => {
      if (n.id !== npc.id) return n
      const { voice, ...rest } = n
      if (sound.trim() === '') return rest
      return { ...rest, voice: pitch === '' || Number(pitch) === 1 ? sound.trim() : { sound: sound.trim(), pitch: Number(pitch) } }
    }))
  }

  const setGreeting = (lines) => {
    setNpcs((ns) => ns.map((n) => {
      if (n.id !== npc.id) return n
      const { greeting, ...rest } = n
      return lines.length ? { ...rest, greeting: lines } : rest
    }))
  }

  const deleteNpc = () => {
    const warning = placed.length ? `\n${placed.length} placed in the world will disappear on publish.` : ''
    if (!window.confirm(`Delete NPC '${npc.name}'?${warning}`)) return
    setNpcs((ns) => ns.filter((n) => n.id !== npc.id))
    setSelected(null)
  }

  return (
    <div style={{ flex: 1, minHeight: 0, display: hidden ? 'none' : 'flex' }}>
      <aside style={{ width: 260, borderRight: '1px solid #ddd', display: 'flex', flexDirection: 'column', fontSize: 12 }}>
        <div style={{ display: 'flex', gap: 6, padding: 10, borderBottom: '1px solid #eee' }}>
          <button onClick={newFolder} disabled={status !== 'ok'} style={toolButton}>+ Folder</button>
          <button onClick={newNpc} disabled={status !== 'ok'} style={toolButton}>+ NPC</button>
        </div>
        <div style={{ flex: 1, overflowY: 'auto', padding: 6 }}>
          {folders.length === 0 && npcs.length === 0
            ? <div style={{ ...hint, padding: 6 }}>No NPCs yet. Create one with "+ NPC".</div>
            : (
              <FolderTree
                folders={folders} selected={selected} onSelect={setSelected}
                collapsed={collapsed} setCollapsed={setCollapsed}
                items={npcs.map((n) => ({ id: n.id, label: n.name, folder: n.folder, note: `${(placements[n.id] || []).length} placed` }))}
              />
            )}
        </div>
      </aside>

      <main style={{ flex: 1, minWidth: 0, overflowY: 'auto', padding: '16px 24px', fontSize: 13 }}>
        <div style={{ maxWidth: 680 }}>
          {npc ? (
            <>
              <div style={{ ...hint, marginBottom: 4 }}>{npc.folder ? folderPath(folders, npc.folder) : '(top)'}</div>
              <label style={label}>
                <div style={{ marginBottom: 3 }}>Name <span style={hint}>(shown above the NPC in game)</span></div>
                <input value={npc.name} onChange={(e) => setField('name', e.target.value)} style={{ ...input, fontSize: 15 }} />
              </label>
              <div style={{ ...hint, marginBottom: 12 }}>id: {npc.id} (fixed)</div>
              <label style={{ ...label, marginBottom: 14 }}>
                <div style={{ marginBottom: 3 }}>Folder</div>
                <FolderSelect folders={folders} value={npc.folder} onChange={(v) => setField('folder', v)} />
              </label>

              <Section title="Look">
                <label style={label}>
                  <div style={{ marginBottom: 3 }}>Model <span style={hint}>(empty = default look)</span></div>
                  <input value={npc.model ?? ''} placeholder="e.g. chief" onChange={(e) => setField('model', e.target.value)} style={input} />
                </label>
                <label style={label}>
                  <div style={{ marginBottom: 3 }}>Idle animation <span style={hint}>(loops)</span></div>
                  <input value={npc.idle ?? ''} placeholder="e.g. animation.chief.wave" onChange={(e) => setField('idle', e.target.value)} style={input} />
                </label>
                <div style={{ marginBottom: 3 }}>
                  While talking <span style={hint}>(only the talking player sees it; empty = keeps its idle)</span>
                </div>
                <div style={{ display: 'flex', gap: 8 }}>
                  {[['start', 'Start', 'once', 'animation.chief.talk_start'],
                    ['loop', 'Loop', 'repeats', 'animation.chief.talk'],
                    ['end', 'End', 'once, on closing', 'animation.chief.talk_end']].map(([key, name, when, example]) => (
                    <label key={key} style={{ flex: 1, minWidth: 0 }}>
                      <div style={{ marginBottom: 3 }}>{name} <span style={hint}>({when})</span></div>
                      <input value={npc.talk?.[key] ?? ''} placeholder={example} onChange={(e) => setTalk(key, e.target.value)} style={input} />
                    </label>
                  ))}
                </div>
                <div style={{ display: 'flex', gap: 8, marginTop: 10 }}>
                  <label style={{ flex: 1, minWidth: 0 }}>
                    <div style={{ marginBottom: 3 }}>
                      Voice <span style={hint}>(each letter as its lines type out; only the talking player hears it; empty = silent)</span>
                    </div>
                    <input value={voiceSound} placeholder="e.g. minecraft:block.note_block.bass"
                      onChange={(e) => setVoice(e.target.value, voicePitch)} style={input} />
                  </label>
                  <label style={{ width: 90 }}>
                    <div style={{ marginBottom: 3 }}>Pitch <span style={hint}>(0.5–2)</span></div>
                    <input type="number" step="0.1" min="0.5" max="2" value={voicePitch} placeholder="1" disabled={!voiceSound}
                      onChange={(e) => setVoice(voiceSound, e.target.value)} style={input} />
                  </label>
                </div>
              </Section>

              <Section title="Dialogue">
                <div style={{ marginBottom: 3 }}>
                  Greeting <span style={hint}>(when the player has nothing to do with this NPC; one page per line; + case to greet by quest state)</span>
                </div>
                <SpeechEditor value={npc.greeting} onChange={setGreeting} placeholder="오, 자네 왔군." quests={quests} />
              </Section>

              <Section title="Placed in the world">
                {placed.length === 0 ? (
                  <div style={{ color: '#888' }}>Not placed yet.</div>
                ) : (
                  <ul style={{ margin: 0, paddingLeft: 16, color: '#555' }}>
                    {placed.map((p, i) => <li key={i}>{p.dim.replace('minecraft:', '')} {p.x}, {p.y}, {p.z}</li>)}
                  </ul>
                )}
                <div style={{ ...hint, marginTop: 8 }}>
                  To place one, publish, then in game: <code>/lorebench npc spawn {npc.id}</code>
                </div>
              </Section>

              <button onClick={deleteNpc} style={{ padding: '5px 10px', cursor: 'pointer', color: '#c0392b' }}>Delete NPC</button>
            </>
          ) : folder ? (
            <FolderPanel
              folder={folder} folders={folders} setFolders={setFolders} items={npcs} setItems={setNpcs}
              onDeleted={(up) => setSelected(up ? { kind: 'folder', id: up } : null)}
            />
          ) : (
            <div style={{ color: '#888' }}>Choose an NPC or folder on the left, or create one.</div>
          )}
        </div>
      </main>
    </div>
  )
}
