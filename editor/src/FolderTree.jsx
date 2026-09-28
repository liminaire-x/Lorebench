import { newId } from './ids.js'

// Folders group quests, NPCs and graphs in the editor; the game doesn't see them
// (Folders.java). Each document keeps its own list: [{ id, name, parent? }], and each
// quest, NPC or graph may name its `folder`. A missing parent or folder means the top.

const input = { width: '100%', padding: '4px 6px', boxSizing: 'border-box' }
const hint = { color: '#888', fontSize: 11 }

export const byText = (a, b) => a.localeCompare(b, 'ko')

// "마을 / 촌장" for a folder, from the top down.
export function folderPath(folders, id) {
  const byId = Object.fromEntries(folders.map((f) => [f.id, f]))
  const names = []
  for (let f = byId[id]; f && names.length <= folders.length; f = byId[f.parent]) names.unshift(f.name)
  return names.join(' / ')
}

// The folder and every folder inside it: none of them can become its parent.
function folderAndInside(folders, id) {
  const out = new Set([id])
  for (let grew = true; grew;) {
    grew = false
    folders.forEach((f) => { if (f.parent && out.has(f.parent) && !out.has(f.id)) { out.add(f.id); grew = true } })
  }
  return out
}

// A copy of a quest, NPC, graph or folder placed in `folder` under `key` ('' = the top).
export function placeIn(thing, folder, key = 'folder') {
  const next = { ...thing }
  if (folder) next[key] = folder; else delete next[key]
  return next
}

// Asks for a name and adds a folder inside `parent`; returns the new id, or null.
export function addFolder(folders, setFolders, parent) {
  const name = window.prompt('Folder name:')
  if (name == null || !name.trim()) return null
  const id = newId('folder', folders.map((f) => f.id))
  setFolders((fs) => fs.concat(placeIn({ id, name: name.trim() }, parent, 'parent')))
  return id
}

export function FolderSelect({ folders, value, onChange, exclude = new Set() }) {
  const options = folders.filter((f) => !exclude.has(f.id))
    .map((f) => ({ id: f.id, label: folderPath(folders, f.id) }))
    .sort((a, b) => byText(a.label, b.label))
  return (
    <select value={value || ''} onChange={(e) => onChange(e.target.value)} style={input}>
      <option value="">(top)</option>
      {options.map((f) => <option key={f.id} value={f.id}>{f.label}</option>)}
    </select>
  )
}

// Folders first, then items, each sorted by name. `items` are { id, label, folder, note? };
// `selected` is { kind: 'folder' | 'item', id }, or something inside an item ({ kind, id, item },
// e.g. a quest's stage). A folder holding the selection stays open. `itemChildren(item, depth)`
// draws what sits under an item (a quest's stages), `itemProps(item)` adds handlers to its row.
export function FolderTree({ folders, items, selected, onSelect, collapsed, setCollapsed, itemChildren, itemProps }) {
  const byId = Object.fromEntries(folders.map((f) => [f.id, f]))
  const selectedIn = selected?.kind === 'folder' ? byId[selected.id]?.parent
    : items.find((i) => i.id === (selected?.item ?? selected?.id))?.folder
  const holdsSelection = new Set()
  for (let f = byId[selectedIn]; f && !holdsSelection.has(f.id); f = byId[f.parent]) holdsSelection.add(f.id)

  const toggle = (id) => setCollapsed((c) => { const n = new Set(c); if (!n.delete(id)) n.add(id); return n })
  const row = (depth, isSelected) => ({
    display: 'flex', alignItems: 'center', gap: 4, width: '100%', textAlign: 'left', cursor: 'pointer',
    padding: `3px 6px 3px ${6 + depth * 14}px`, border: 'none', borderRadius: 4, fontSize: 12,
    background: isSelected ? '#e0e7ff' : 'transparent',
  })

  const level = (parent, depth) => (
    <>
      {folders.filter((f) => (f.parent || '') === parent).sort((a, b) => byText(a.name, b.name)).map((f) => {
        const open = !collapsed.has(f.id) || holdsSelection.has(f.id)
        return (
          <div key={f.id}>
            <button
              onClick={() => onSelect({ kind: 'folder', id: f.id })}
              style={{ ...row(depth, selected?.kind === 'folder' && selected.id === f.id), fontWeight: 600 }}
            >
              <span
                onClick={(e) => { e.stopPropagation(); toggle(f.id) }}
                style={{ width: 12, color: '#666' }}
                title={open ? 'Collapse' : 'Expand'}
              >{open ? '▾' : '▸'}</span>
              {f.name}
            </button>
            {open && level(f.id, depth + 1)}
          </div>
        )
      })}
      {items.filter((i) => (i.folder || '') === parent).sort((a, b) => byText(a.label, b.label)).map((i) => {
        const { style, ...handlers } = itemProps ? itemProps(i) : {}
        return (
          <div key={i.id}>
            <button
              onClick={() => onSelect({ kind: 'item', id: i.id })}
              {...handlers}
              style={{ ...row(depth, selected?.kind === 'item' && selected.id === i.id), ...style }}
            >
              <span style={{ width: 12 }} />
              <span style={{ flex: 1 }}>{i.label}</span>
              {i.note && <span style={hint}>{i.note}</span>}
            </button>
            {itemChildren && itemChildren(i, depth + 1)}
          </div>
        )
      })}
    </>
  )
  return level('', 0)
}

// The chosen folder: its name, where it sits, and deleting it. What was inside a
// deleted folder moves up to its parent; nothing else is lost.
export function FolderPanel({ folder, folders, setFolders, items, setItems, onDeleted }) {
  const set = (key, value) => setFolders((fs) => fs.map((f) => {
    if (f.id !== folder.id) return f
    return key === 'parent' ? placeIn(f, value, 'parent') : { ...f, [key]: value }
  }))

  const remove = () => {
    const up = folder.parent || ''
    const where = up ? `'${folderPath(folders, up)}'` : 'the top'
    if (!window.confirm(`Delete folder '${folder.name}'? What's inside moves up to ${where}.`)) return
    setFolders((fs) => fs.filter((f) => f.id !== folder.id)
      .map((f) => (f.parent === folder.id ? placeIn(f, up, 'parent') : f)))
    setItems((xs) => xs.map((x) => (x.folder === folder.id ? placeIn(x, up) : x)))
    onDeleted(up)
  }

  return (
    <>
      <div style={{ ...hint, marginBottom: 4 }}>Folder</div>
      <label style={{ display: 'block', marginBottom: 12 }}>
        <div style={{ marginBottom: 3 }}>Name</div>
        <input value={folder.name} onChange={(e) => set('name', e.target.value)} style={{ ...input, fontSize: 15 }} />
      </label>
      <label style={{ display: 'block', marginBottom: 12 }}>
        <div style={{ marginBottom: 3 }}>Inside</div>
        <FolderSelect folders={folders} value={folder.parent} onChange={(v) => set('parent', v)} exclude={folderAndInside(folders, folder.id)} />
      </label>
      <div style={{ ...hint, marginBottom: 12 }}>
        id: {folder.id} (fixed) · {folders.filter((f) => f.parent === folder.id).length} folders,{' '}
        {items.filter((x) => x.folder === folder.id).length} directly inside.
        Folders only sort things here in the editor; players don't see them.
      </div>
      <button onClick={remove} style={{ padding: '5px 10px', cursor: 'pointer', color: '#c0392b' }}>Delete folder</button>
    </>
  )
}
