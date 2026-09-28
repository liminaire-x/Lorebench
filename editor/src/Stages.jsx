import { useEffect } from 'react'
import { newId } from './ids.js'

// A quest's stages in the quest tree (0015): under the open quest, in the author's order,
// dragged to reorder or onto another quest to move there. Right-click menus add and delete.
// Dragging uses the browser's own drag and drop; nothing is saved until Publish.

const hint = { color: '#888', fontSize: 11 }

// Every stage id in the document: a stage's id is unique in all of it.
export const stageIds = (quests) => quests.flatMap((q) => (q.stages || []).map((st) => st.id))

// The id a stage gets moved from quest `from` to `to`: its own within one quest, a new one
// in another (0015).
export const movedId = (quests, from, stageId, to) => (from === to ? stageId : newId('stage', stageIds(quests)))

// Quests with stage `stageId` of `from` moved to `to` at `index` (among `to`'s stages before
// the move), under `id` (`movedId`).
export function moveStage(quests, from, stageId, to, index, id) {
  const source = quests.find((q) => q.id === from)
  const moving = source?.stages.find((st) => st.id === stageId)
  if (!moving) return quests
  return quests.map((q) => {
    let stages = q.stages
    if (q.id === from) stages = stages.filter((st) => st.id !== stageId)
    if (q.id === to) {
      const at = from === to && source.stages.indexOf(moving) < index ? index - 1 : index
      stages = [...stages.slice(0, at), { ...moving, id }, ...stages.slice(at)]
    }
    return stages === q.stages ? q : { ...q, stages }
  })
}

// Whether a stage uses the quest's own items, which are marked with the quest (0012): moved
// to another quest, they no longer count there.
export const usesQuestItems = (st) => (st.goals || []).some((g) => g.collect !== undefined)
  || (st.gives || []).some((g) => g.quest)

// How many players are on a stage of a published quest, or null if the game can't be asked.
export async function playersOn(questId, stageId) {
  try {
    const r = await fetch('/api/quest-players?quest=' + encodeURIComponent(questId)).then((res) => res.json())
    return r.error ? null : (r.players || []).filter((p) => p.stage === stageId).length
  } catch (e) {
    return null
  }
}

// Who is on a stage that leaves its quest, for a confirm message.
export function whoIsOn(count, questTitle) {
  if (count === null) return `The game can't be asked now who is on it; anyone on it goes back to a stage of '${questTitle}' you choose when you publish.`
  if (count === 0) return `Nobody is on it in '${questTitle}'.`
  return `${count} player${count === 1 ? ' is' : 's are'} on it in '${questTitle}': when you publish, choose which stage of '${questTitle}' they go back to.`
}

// The stages of `quest` as rows under it. `drag` is { quest, stage } while a stage is dragged;
// `dropAt` is { quest, index } where it would land.
export function StageRows({ quest, depth, selected, onSelect, drag, setDrag, dropAt, setDropAt, onDrop, onMenu }) {
  const stages = quest.stages || []
  const row = (isSelected) => ({
    display: 'flex', alignItems: 'center', gap: 4, width: '100%', textAlign: 'left', cursor: 'pointer',
    padding: `3px 6px 3px ${6 + depth * 14}px`, border: 'none', borderRadius: 4, fontSize: 12,
    background: isSelected ? '#e0e7ff' : 'transparent',
  })
  const line = (on) => ({ height: 2, margin: '0 6px', background: on ? '#2563eb' : 'transparent' })
  const at = (i) => dropAt && dropAt.quest === quest.id && dropAt.index === i
  return (
    <div>
      {stages.map((st, i) => (
        <div key={st.id}>
          <div style={line(at(i))} />
          <button
            draggable
            onClick={() => onSelect({ kind: 'stage', id: st.id, item: quest.id })}
            onContextMenu={(e) => { e.preventDefault(); onMenu(e, quest, st) }}
            onDragStart={(e) => {
              e.dataTransfer.setData('text/plain', st.id)
              e.dataTransfer.effectAllowed = 'move'
              setDrag({ quest: quest.id, stage: st.id })
            }}
            onDragEnd={() => { setDrag(null); setDropAt(null) }}
            onDragOver={(e) => {
              if (!drag) return
              e.preventDefault()
              const box = e.currentTarget.getBoundingClientRect()
              const index = e.clientY < box.top + box.height / 2 ? i : i + 1
              if (!at(index)) setDropAt({ quest: quest.id, index })
            }}
            onDrop={(e) => { e.preventDefault(); if (dropAt) onDrop(dropAt.quest, dropAt.index) }}
            style={{ ...row(selected?.kind === 'stage' && selected.id === st.id), opacity: drag?.stage === st.id ? 0.4 : 1 }}
            title="Drag to reorder, or onto another quest to move it there. Right-click to delete."
          >
            <span style={{ width: 12 }} />
            <span style={{ ...hint, minWidth: 14 }}>{i + 1}.</span>
            <span style={{ flex: 1 }}>{st.text || <span style={hint}>(no text)</span>}</span>
          </button>
        </div>
      ))}
      <div style={line(at(stages.length))} />
    </div>
  )
}

// A small menu at the mouse: { x, y, items: [{ label, run, danger? }] }. Closes on a click
// elsewhere, Escape, or leaving the window.
export function ContextMenu({ menu, onClose }) {
  useEffect(() => {
    if (!menu) return undefined
    const key = (e) => { if (e.key === 'Escape') onClose() }
    window.addEventListener('mousedown', onClose)
    window.addEventListener('keydown', key)
    window.addEventListener('blur', onClose)
    return () => {
      window.removeEventListener('mousedown', onClose)
      window.removeEventListener('keydown', key)
      window.removeEventListener('blur', onClose)
    }
  }, [menu, onClose])
  if (!menu) return null
  return (
    <div
      onMouseDown={(e) => e.stopPropagation()}
      style={{
        position: 'fixed', left: menu.x, top: menu.y, zIndex: 20, background: '#fff', border: '1px solid #ccc',
        borderRadius: 5, boxShadow: '0 2px 8px rgba(0,0,0,0.15)', padding: 3, minWidth: 140, fontSize: 12,
      }}
    >
      {menu.items.map((it) => (
        <button
          key={it.label}
          onClick={() => { onClose(); it.run() }}
          style={{
            display: 'block', width: '100%', textAlign: 'left', padding: '5px 10px', border: 'none',
            background: 'transparent', cursor: 'pointer', color: it.danger ? '#c0392b' : 'inherit',
          }}
        >{it.label}</button>
      ))}
    </div>
  )
}
