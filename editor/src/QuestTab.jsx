import { useCallback, useEffect, useState } from 'react'
import { newId } from './ids.js'
import { FolderPanel, FolderSelect, FolderTree, addFolder, byText, folderPath, placeIn } from './FolderTree.jsx'
import { Section, SpeechEditor } from './Section.jsx'

const input = { width: '100%', padding: '4px 6px', boxSizing: 'border-box' }
const label = { display: 'block', marginBottom: 12 }
const hint = { color: '#888', fontSize: 11 }
const toolButton = { padding: '4px 8px', cursor: 'pointer', color: '#2563eb', border: '1px solid #ddd', borderRadius: 5, background: '#fafafa' }

// The kinds of goal, by the key that names the target (see QuestFormat.java).
const GOAL_KINDS = [
  { key: 'item', label: 'hand in', placeholder: 'minecraft:wheat' },
  { key: 'kill', label: 'kill', placeholder: 'minecraft:wolf' },
  { key: 'harvest', label: 'harvest', placeholder: 'minecraft:wheat', list: 'crops', choose: 'Choose a crop…' },
  { key: 'breed', label: 'breed', placeholder: 'minecraft:cow', list: 'animals', choose: 'Choose an animal…' },
  { key: 'collect', label: 'collect (drops)', placeholder: "minecraft:amethyst_shard[custom_name='\"목걸이 조각\"']" },
]

// A harvest goal's crop or a breed goal's animal: picked from the game's list
// ([{ id, name }]), or typed when the list can't be read.
function ListPicker({ value, options, placeholder, choose, onChange }) {
  if (!options.length) {
    return <input value={value} placeholder={placeholder} onChange={(e) => onChange(e.target.value)} style={{ ...input, flex: 1 }} />
  }
  const known = options.some((c) => c.id === value)
  return (
    <select value={value} onChange={(e) => onChange(e.target.value)} style={{ ...input, flex: 1 }}>
      {!value && <option value="">{choose}</option>}
      {value && !known && <option value={value}>{value} (not in this game)</option>}
      {options.map((c) => <option key={c.id} value={c.id}>{c.name} ({c.id})</option>)}
    </select>
  )
}

// A quest's goals or rewards: rows of target + count. Goals pick hand in / kill / harvest / breed.
// Rewards (`wide`) take a whole /give line such as minecraft:iron_sword[custom_name=...],
// so the item gets its own line. A stage's gifts (`gifts`) may be the quest's own items (0015).
function StackList({ stacks, onChange, fetchHeld, lists = {}, wide = false, goals = false, gifts = false }) {
  const set = (i, key, value) => onChange(stacks.map((s, j) => (j === i ? { ...s, [key]: value } : s)))
  // A true-or-nothing flag (keep, quest): unticked drops it.
  const flag = (i, key, on) => onChange(stacks.map((s, j) => {
    if (j !== i) return s
    const { [key]: _, ...rest } = s
    return on ? { ...rest, [key]: true } : rest
  }))
  // "Show only" (0015): handing in leaves the items with the player.
  const keepBox = (i) => (
    <label title="Only shown: handing in leaves these with the player" style={{ display: 'flex', alignItems: 'center', gap: 2, fontSize: 11, whiteSpace: 'nowrap' }}>
      <input type="checkbox" checked={!!stacks[i].keep} onChange={(e) => flag(i, 'keep', e.target.checked)} />show only
    </label>
  )
  return (
    <div style={{ marginBottom: 12 }}>
      {stacks.map((s, i) => {
        const count = (
          <input
            type="number" min={1} value={s.count}
            onChange={(e) => set(i, 'count', e.target.value === '' ? '' : Number(e.target.value))}
            style={{ ...input, width: 60 }}
          />
        )
        const remove = <button onClick={() => onChange(stacks.filter((_, j) => j !== i))} style={{ cursor: 'pointer' }}>×</button>
        // Fill this row's item with what the chosen player holds in game.
        const held = (key) => (
          <button
            title="Use the item the chosen player is holding"
            onClick={async () => { const spec = await fetchHeld(); if (spec) set(i, key, spec) }}
            style={{ cursor: 'pointer' }}
          >✋</button>
        )
        if (goals) {
          // A goal is { item, count } (hand in), or something done while active:
          // { kill, count }, { harvest, count } (fully grown crops, one per plant) or
          // { breed, count } (babies born to animals the player fed), or a quest item
          // { collect, count, from, chance } that drops only for the player on the quest (0012);
          // without from, one an earlier stage brought (0015). Hand-ins may keep: only shown.
          const kindDef = GOAL_KINDS.find((k) => s[k.key] !== undefined) || GOAL_KINDS[0]
          const kind = kindDef.key
          const setKind = (k) => onChange(stacks.map((x, j) => (j === i
            ? { [k]: x[kind], count: x.count, ...(k === 'item' || k === 'collect') && x.keep ? { keep: true } : {} } : x)))
          const kindSelect = (
            <select value={kind} onChange={(e) => setKind(e.target.value)} style={{ padding: '4px 2px' }}>
              {GOAL_KINDS.map((k) => <option key={k.key} value={k.key}>{k.label}</option>)}
            </select>
          )
          if (kind === 'collect') {
            const mob = (s.from || '').replace(/^kill:/, '')
            // No mob: the items come from an earlier stage and never drop (0015), so no chance either.
            const setMob = (v) => onChange(stacks.map((x, j) => {
              if (j !== i) return x
              const { from, chance, ...rest } = x
              return v.trim() ? { ...rest, from: 'kill:' + v.trim(), ...(chance !== undefined ? { chance } : {}) } : rest
            }))
            const percent = s.chance === undefined ? '' : Math.round(s.chance * 1000) / 10
            const setChance = (v) => onChange(stacks.map((x, j) => {
              if (j !== i) return x
              const { chance, ...rest } = x
              const n = Number(v)
              return v === '' || n >= 100 ? rest : { ...rest, chance: n / 100 }
            }))
            return (
              <div key={i} style={{ marginBottom: 6, borderLeft: '2px solid #e5e7eb', paddingLeft: 6 }}>
                <div style={{ display: 'flex', gap: 4, marginBottom: 3 }}>
                  {kindSelect}
                  <textarea
                    value={s.collect} rows={1} placeholder={kindDef.placeholder}
                    title="The item as /give writes it. It drops only for players on this quest, marked as theirs."
                    onChange={(e) => set(i, 'collect', e.target.value)}
                    style={{ ...input, flex: 1, resize: 'vertical', fontFamily: 'monospace', fontSize: 11 }}
                  />
                  {held('collect')}
                </div>
                <div style={{ display: 'flex', gap: 4, alignItems: 'center', fontSize: 12 }}>
                  drops when they kill
                  <input
                    value={mob} placeholder="none: an earlier stage brought it"
                    title="Empty: the player already has these from an earlier stage (dropped there, or given as a quest item); none drop"
                    onChange={(e) => setMob(e.target.value)} style={{ ...input, flex: 1 }}
                  />
                  {mob && <><input
                    type="number" min={1} max={100} value={percent} placeholder="100"
                    title="Chance per kill, in percent (empty = every time)"
                    onChange={(e) => setChance(e.target.value)} style={{ ...input, width: 60 }}
                  />%</>}
                  {keepBox(i)}{count}{remove}
                </div>
              </div>
            )
          }
          return (
            <div key={i} style={{ display: 'flex', gap: 4, marginBottom: 3 }}>
              {kindSelect}
              {kindDef.list ? (
                <ListPicker
                  value={s[kind]} options={lists[kindDef.list] || []} placeholder={kindDef.placeholder}
                  choose={kindDef.choose} onChange={(v) => set(i, kind, v)}
                />
              ) : (
                <input
                  value={s[kind]} placeholder={kindDef.placeholder}
                  onChange={(e) => set(i, kind, e.target.value)} style={{ ...input, flex: 1 }}
                />
              )}
              {kind === 'item' && held('item')}{kind === 'item' && keepBox(i)}{count}{remove}
            </div>
          )
        }
        return wide ? (
          <div key={i} style={{ marginBottom: 6 }}>
            <textarea
              value={s.item} rows={2} placeholder="minecraft:iron_sword[custom_name='&quot;...&quot;']"
              onChange={(e) => set(i, 'item', e.target.value)}
              style={{ ...input, resize: 'vertical', fontFamily: 'monospace', fontSize: 11 }}
            />
            <div style={{ display: 'flex', gap: 4, justifyContent: 'flex-end', alignItems: 'center' }}>
              {gifts && (
                <label title="This quest's own item: marked for the player, 'Quest Item' in its tooltip, useless to others" style={{ display: 'flex', alignItems: 'center', gap: 2, fontSize: 11, marginRight: 'auto' }}>
                  <input type="checkbox" checked={!!s.quest} onChange={(e) => flag(i, 'quest', e.target.checked)} />quest item
                </label>
              )}
              {held('item')}{count}{remove}
            </div>
          </div>
        ) : (
          <div key={i} style={{ display: 'flex', gap: 4, marginBottom: 3 }}>
            <input value={s.item} placeholder="minecraft:wheat" onChange={(e) => set(i, 'item', e.target.value)} style={{ ...input, flex: 1 }} />
            {count}{remove}
          </div>
        )
      })}
      <button onClick={() => onChange(stacks.concat({ item: '', count: 1 }))} style={{ cursor: 'pointer', color: '#2563eb' }}>+ add</button>
    </div>
  )
}

const STATE_LABEL = { hidden: 'not taken', active: 'in progress', waiting: 'waiting', ready: 'ready to hand in', done: 'done' }

// A player's state; one who handed in to wait (0013) says so, with the day.
const stateText = (p) => {
  if (p.handedDay === undefined) return STATE_LABEL[p.state] || p.state
  return (p.state === 'ready' ? 'ready to take' : STATE_LABEL[p.state] || p.state) + ` · handed in on day ${p.handedDay}`
}

// Who is on this (published) quest, has done it or turned it down, online or not, and a
// way to take one of them back to before it: offered again as the first time, supplies
// given again, progress from 0.
function QuestPlayers({ quest, setMessage }) {
  const [rows, setRows] = useState(null) // null = not read yet
  const load = useCallback(async () => {
    try {
      const r = await fetch('/api/quest-players?quest=' + encodeURIComponent(quest.id)).then((res) => res.json())
      if (r.error) { setMessage({ ok: false, text: r.error }); setRows([]); return }
      setRows(r.players || [])
    } catch (e) { setRows([]) }
  }, [quest.id, setMessage])
  useEffect(() => { setRows(null); load() }, [load])

  // "2/3" for each counted goal (kill, harvest, breed) of the stage they are on, in its order.
  const counts = (p) => ((quest.stages || []).find((st) => st.id === p.stage)?.goals || []).flatMap((g) => {
    const k = ['kill', 'harvest', 'breed'].find((key) => g[key] !== undefined)
    return k ? [`${Math.min(p.progress[`${k}:${g[k]}`] || 0, g.count)}/${g.count}`] : []
  }).join(' · ')

  // The stage they are on (0015); one the quest no longer has stands out, to reset them.
  const stageOf = (p) => {
    if (!p.stage) return null
    const i = (quest.stages || []).findIndex((st) => st.id === p.stage)
    return i < 0
      ? <span style={{ color: '#c0392b', marginLeft: 6 }}>on a removed stage ({p.stage})</span>
      : <span style={{ ...hint, marginLeft: 6 }}>stage {i + 1}: {quest.stages[i].text || '(no text)'}</span>
  }

  const reset = async (p) => {
    if (!window.confirm(`Take ${p.name} back to before "${quest.title}"?\n\nIt will be offered again as the first time (refusals and any wait forgotten), supplies are given again on accepting, and progress starts from 0. What they already got stays theirs.`)) return
    try {
      const r = await fetch('/api/quest-reset', { method: 'POST', body: JSON.stringify({ quest: quest.id, player: p.uuid }) }).then((res) => res.json())
      setMessage(r.ok ? { ok: true, text: `${p.name} is back to before "${quest.title}".` } : { ok: false, text: r.error || 'Reset failed.' })
    } catch (e) {
      setMessage({ ok: false, text: 'Reset failed: ' + e })
    }
    load()
  }

  return (
    <Section title="Players">
      <div style={{ display: 'flex', alignItems: 'center', gap: 6, marginBottom: 6 }}>
        <span style={hint}>Who is on this quest, has done it or turned it down (as last published).</span>
        <button onClick={load} title="Refresh" style={{ cursor: 'pointer', marginLeft: 'auto' }}>↻</button>
      </div>
      {rows === null ? <div style={hint}>Reading…</div>
        : rows.length === 0 ? <div style={hint}>Nobody yet.</div>
        : rows.map((p) => (
          <div key={p.uuid} style={{ display: 'flex', alignItems: 'center', gap: 6, marginBottom: 3 }}>
            <span style={{ flex: 1 }}>
              {p.name}{!p.online && <span style={hint}> (offline)</span>}
              <span style={{ ...hint, marginLeft: 6 }}>{stateText(p)}{counts(p) ? ' · ' + counts(p) : ''}{p.timesDeclined ? ` · declined ${p.timesDeclined}×` : ''}</span>
              {stageOf(p)}
            </span>
            <button onClick={() => reset(p)} style={{ cursor: 'pointer', color: '#c0392b' }}>Reset</button>
          </div>
        ))}
    </Section>
  )
}

// The quest tab: a folder tree on the left, the chosen quest or folder on the right.
export default function QuestTab({ quests, setQuests, folders, setFolders, npcs, status, setMessage, hidden }) {
  const [selected, setSelected] = useState(null) // { kind: 'item' | 'folder', id }
  const [collapsed, setCollapsed] = useState(() => new Set()) // folder ids
  const [players, setPlayers] = useState([]) // online, for "use held item"
  const [heldPlayer, setHeldPlayer] = useState(() => {
    try { return localStorage.getItem('lorebench.heldPlayer') || '' } catch (e) { return '' }
  })

  const quest = selected?.kind === 'item' ? quests.find((q) => q.id === selected.id) || null : null
  const folder = selected?.kind === 'folder' ? folders.find((f) => f.id === selected.id) || null : null
  // The stage being edited (0015): the chosen one, else the first.
  const [stageId, setStageId] = useState(null)
  const stages = quest?.stages || []
  const stage = stages.find((st) => st.id === stageId) || stages[0] || null

  // Where "+ Folder" and "+ Quest" put the new thing: the chosen folder, or the chosen quest's folder.
  const target = folder?.id ?? quest?.folder ?? ''

  const loadPlayers = useCallback(async () => {
    try {
      setPlayers((await fetch('/api/players').then((r) => r.json())).players || [])
    } catch (e) { /* shown as offline elsewhere */ }
  }, [])
  const questId = quest?.id
  useEffect(() => { if (questId) loadPlayers() }, [questId, loadPlayers])

  // The crops a harvest goal and the animals a breed goal may name, from the running
  // game (other mods' too). Read once; if the game can't be reached, they are typed.
  const [lists, setLists] = useState({})
  const listsLoaded = Object.keys(lists).length > 0
  useEffect(() => {
    if (!questId || listsLoaded) return
    const read = (path, key) => fetch(path).then((r) => r.json()).then((r) => r[key] || []).catch(() => [])
    Promise.all([read('/api/crops', 'crops'), read('/api/animals', 'animals')])
      .then(([crops, animals]) => { if (crops.length || animals.length) setLists({ crops, animals }) })
  }, [questId, listsLoaded])

  const pickHeldPlayer = useCallback((name) => {
    setHeldPlayer(name)
    try { localStorage.setItem('lorebench.heldPlayer', name) } catch (e) { /* not remembered */ }
  }, [])

  // What the chosen player holds, as /give writes it; null (with a message) if none.
  const fetchHeld = useCallback(async () => {
    if (!heldPlayer) { setMessage({ ok: false, text: 'Choose whose held item to use (✋ held item of, in the stage).' }); return null }
    try {
      const r = await fetch('/api/held-item?player=' + encodeURIComponent(heldPlayer)).then((res) => res.json())
      if (r.error) { setMessage({ ok: false, text: r.error }); return null }
      return r.item
    } catch (e) {
      setMessage({ ok: false, text: 'Reading the held item failed: ' + e })
      return null
    }
  }, [heldPlayer, setMessage])

  const newFolder = () => {
    const id = addFolder(folders, setFolders, target)
    if (id) setSelected({ kind: 'folder', id })
  }

  const newQuest = () => {
    const title = window.prompt('Quest title:')
    if (title == null || !title.trim()) return
    const id = newId('quest', quests.map((q) => q.id))
    const stage = { id: newId('stage', quests.flatMap((q) => (q.stages || []).map((st) => st.id))), text: '' }
    setQuests((qs) => qs.concat(placeIn({ id, title: title.trim(), stages: [stage], rewards: [] }, target)))
    setSelected({ kind: 'item', id })
  }

  // Edit one field of the chosen quest; an emptied optional field is dropped.
  const setQuestField = (key, value) => {
    setQuests((qs) => qs.map((q) => {
      if (q.id !== quest.id) return q
      const next = { ...q, [key]: value }
      if (['icon', 'text', 'folder', 'giver'].includes(key) && value.trim() === '') delete next[key]
      return next
    }))
  }

  // Edit the chosen stage with `change(stage)`, which returns the new one.
  const changeStage = (change) => {
    setQuests((qs) => qs.map((q) => (q.id !== quest.id ? q
      : { ...q, stages: q.stages.map((st) => (st.id === stage.id ? change(st) : st)) })))
  }
  // One field of the chosen stage; an emptied optional one (to, goals, gives) is dropped.
  const setStageField = (key, value) => changeStage((st) => {
    const next = { ...st, [key]: value }
    if ((key === 'to' && value === '') || ((key === 'goals' || key === 'gives') && !value.length)) delete next[key]
    return next
  })
  // Game days between handing the stage in and going on (0013): { days: n }, none when empty.
  const setWait = (text) => changeStage((st) => {
    const { wait, ...rest } = st
    return text.trim() === '' ? rest : { ...rest, wait: { days: Number(text) } }
  })
  const setStageLines = (key, lines) => changeStage((st) => {
    const all = { ...st.lines, [key]: lines }
    if (!lines.length) delete all[key]
    const { lines: _, ...rest } = st
    return Object.keys(all).length ? { ...rest, lines: all } : rest
  })

  // Required quests and dialogue lines: an emptied list is dropped (0009).
  const setRequires = (ids) => {
    setQuests((qs) => qs.map((q) => {
      if (q.id !== quest.id) return q
      const { requires, ...rest } = q
      return ids.length ? { ...rest, requires: ids } : rest
    }))
  }
  const setLines = (key, lines) => {
    setQuests((qs) => qs.map((q) => {
      if (q.id !== quest.id) return q
      const all = { ...q.lines, [key]: lines }
      if (!lines.length) delete all[key]
      const { lines: _, ...rest } = q
      return Object.keys(all).length ? { ...rest, lines: all } : rest
    }))
  }

  const npcOptions = [...npcs].sort((a, b) => byText(a.name, b.name))
    .map((n) => <option key={n.id} value={n.id}>{n.name}</option>)

  const deleteQuest = () => {
    if (!window.confirm(`Delete quest '${quest.title}'? Players keep their progress records.`)) return
    setQuests((qs) => qs.filter((q) => q.id !== quest.id))
    setSelected(null)
  }

  return (
    <div style={{ flex: 1, minHeight: 0, display: hidden ? 'none' : 'flex' }}>
      <aside style={{ width: 260, borderRight: '1px solid #ddd', display: 'flex', flexDirection: 'column', fontSize: 12 }}>
        <div style={{ display: 'flex', gap: 6, padding: 10, borderBottom: '1px solid #eee' }}>
          <button onClick={newFolder} disabled={status !== 'ok'} style={toolButton}>+ Folder</button>
          <button onClick={newQuest} disabled={status !== 'ok'} style={toolButton}>+ Quest</button>
        </div>
        <div style={{ flex: 1, overflowY: 'auto', padding: 6 }}>
          {folders.length === 0 && quests.length === 0
            ? <div style={{ ...hint, padding: 6 }}>No quests yet. Create one with "+ Quest".</div>
            : (
              <FolderTree
                folders={folders} selected={selected} onSelect={setSelected}
                collapsed={collapsed} setCollapsed={setCollapsed}
                items={quests.map((q) => ({ id: q.id, label: q.title, folder: q.folder }))}
              />
            )}
        </div>
      </aside>

      <main style={{ flex: 1, minWidth: 0, overflowY: 'auto', padding: '16px 24px', fontSize: 13 }}>
        <div style={{ maxWidth: 680 }}>
          {quest ? (
            <>
              <div style={{ ...hint, marginBottom: 4 }}>{quest.folder ? folderPath(folders, quest.folder) : '(top)'}</div>
              <label style={label}>
                <div style={{ marginBottom: 3 }}>Title</div>
                <input value={quest.title} onChange={(e) => setQuestField('title', e.target.value)} style={{ ...input, fontSize: 15 }} />
              </label>
              <div style={{ ...hint, marginBottom: 12 }}>id: {quest.id} (fixed)</div>

              <Section title="Basics">
                <label style={label}>
                  <div style={{ marginBottom: 3 }}>Folder</div>
                  <FolderSelect folders={folders} value={quest.folder} onChange={(v) => setQuestField('folder', v)} />
                </label>
                <label style={label}>
                  <div style={{ marginBottom: 3 }}>Icon <span style={hint}>(item id; empty = the first stage's first need)</span></div>
                  <input
                    value={quest.icon ?? ''}
                    placeholder="e.g. minecraft:wheat"
                    onChange={(e) => setQuestField('icon', e.target.value)}
                    style={input}
                  />
                </label>
                <label style={{ ...label, marginBottom: 0 }}>
                  <div style={{ marginBottom: 3 }}>Text <span style={hint}>(shown in the quest screen)</span></div>
                  <textarea
                    value={quest.text ?? ''}
                    rows={5}
                    onChange={(e) => setQuestField('text', e.target.value)}
                    style={{ ...input, resize: 'vertical', fontFamily: 'inherit' }}
                  />
                </label>
              </Section>

              <Section title="Flow">
                <label style={label}>
                  <div style={{ marginBottom: 3 }}>Given by <span style={hint}>(offers it when the player talks; none = only graphs reveal it)</span></div>
                  <select value={quest.giver ?? ''} onChange={(e) => setQuestField('giver', e.target.value)} style={input}>
                    <option value="">(none)</option>
                    {npcOptions}
                  </select>
                </label>
                <div style={{ marginBottom: 3 }}>Requires <span style={hint}>(all of these done before it is offered)</span></div>
                {(quest.requires || []).map((r, i) => (
                  <div key={i} style={{ display: 'flex', gap: 4, marginBottom: 3 }}>
                    <select
                      value={r}
                      onChange={(e) => setRequires(quest.requires.map((x, j) => (j === i ? e.target.value : x)))}
                      style={{ ...input, flex: 1 }}
                    >
                      {quests.filter((q) => q.id !== quest.id).sort((a, b) => byText(a.title, b.title))
                        .map((q) => <option key={q.id} value={q.id}>{q.title}</option>)}
                    </select>
                    <button onClick={() => setRequires(quest.requires.filter((_, j) => j !== i))} style={{ cursor: 'pointer' }}>×</button>
                  </div>
                ))}
                {quests.some((q) => q.id !== quest.id && !(quest.requires || []).includes(q.id)) && (
                  <button
                    onClick={() => setRequires((quest.requires || []).concat(
                      quests.find((q) => q.id !== quest.id && !(quest.requires || []).includes(q.id)).id))}
                    style={{ cursor: 'pointer', color: '#2563eb' }}
                  >+ add</button>
                )}
              </Section>

              <Section title="Dialogue">
                <div style={{ ...hint, marginBottom: 8 }}>What the giver says about taking it, one page per line. + case says something else when a condition holds (the first case that holds is said). What is said during a stage is in the stage.</div>
                <div style={{ marginBottom: 3 }}>Offer <span style={hint}>(the giver, before Accept / Decline)</span></div>
                <SpeechEditor value={quest.lines?.offer} onChange={(v) => setLines('offer', v)} placeholder="밀 10개만 구해다 주겠나?" inQuest quests={quests} />
                <div style={{ margin: '10px 0 3px' }}>After accepting <span style={hint}>(the giver, right after Accept)</span></div>
                <SpeechEditor value={quest.lines?.accepted} onChange={(v) => setLines('accepted', v)} placeholder="자, 이 씨앗으로 시작하게." inQuest quests={quests} />
                <div style={{ margin: '10px 0 3px' }}>After declining <span style={hint}>(the giver, right after Decline; offered again next time)</span></div>
                <SpeechEditor value={quest.lines?.declined} onChange={(v) => setLines('declined', v)} placeholder="그래… 무리한 부탁이지." inQuest quests={quests} />
              </Section>

              {stage && (
                <Section title="Stage">
                  {stages.length > 1 && (
                    <label style={label}>
                      <select value={stage.id} onChange={(e) => setStageId(e.target.value)} style={input}>
                        {stages.map((st, i) => <option key={st.id} value={st.id}>{i + 1}. {st.text || '(no text)'}</option>)}
                      </select>
                    </label>
                  )}
                  <div style={{ ...hint, marginBottom: 8 }}>id: {stage.id} (fixed; players' records name the stage they are on by it)</div>
                  <label style={label}>
                    <div style={{ marginBottom: 3 }}>Text <span style={hint}>(what to do, a line in the quest screen)</span></div>
                    <input value={stage.text ?? ''} placeholder="e.g. 촌장에게 밀 가져가기" onChange={(e) => setStageField('text', e.target.value)} style={input} />
                  </label>
                  <label style={label}>
                    <div style={{ marginBottom: 3 }}>Go to <span style={hint}>(the NPC it is done with)</span></div>
                    <select value={stage.to ?? ''} onChange={(e) => setStageField('to', e.target.value)} style={input}>
                      <option value="">(the giver)</option>
                      {npcOptions}
                    </select>
                  </label>
                  <label style={{ display: 'flex', gap: 4, alignItems: 'center', marginBottom: 4 }}>
                    <span>✋ held item of</span>
                    <select value={heldPlayer} onChange={(e) => pickHeldPlayer(e.target.value)} style={{ flex: 1, padding: '3px 2px' }}>
                      <option value="">(player)</option>
                      {(players.includes(heldPlayer) || !heldPlayer ? players : [heldPlayer, ...players]).map((p) => (
                        <option key={p} value={p}>{p}{players.includes(p) ? '' : ' (offline)'}</option>
                      ))}
                    </select>
                    <button onClick={loadPlayers} title="Refresh online players" style={{ cursor: 'pointer' }}>↻</button>
                  </label>
                  <div style={{ ...hint, marginBottom: 10 }}>
                    In a need, only the listed parts must match. Delete damage=… to accept any wear.
                  </div>
                  <div style={{ marginBottom: 3 }}>
                    Needs <span style={hint}>(all of them, in this order; none = just talk to the NPC. Kill, harvest and breed count from 0 in each stage; collect items drop only during this stage, for that player only)</span>
                  </div>
                  <StackList goals fetchHeld={fetchHeld} lists={lists} stacks={stage.goals || []} onChange={(v) => setStageField('goals', v)} />
                  <label style={{ display: 'flex', gap: 4, alignItems: 'center', marginBottom: 10 }}>
                    <span>Wait</span>
                    <input type="number" min="1" value={stage.wait?.days ?? ''} placeholder="none" onChange={(e) => setWait(e.target.value)}
                      style={{ width: 60, padding: '3px 4px' }} />
                    <span>days</span>
                    <span style={hint}>(after Hand over; it goes on once this many mornings pass, 6:00 or waking up. Empty = right away)</span>
                  </label>
                  <div style={{ marginBottom: 3 }}>
                    Gives <span style={hint}>(when the stage is done, after its wait; seen only when given. Tick quest item for the story's own things, e.g. a mended necklace to deliver)</span>
                  </div>
                  <StackList wide gifts fetchHeld={fetchHeld} stacks={stage.gives || []} onChange={(v) => setStageField('gives', v)} />
                  <div style={{ margin: '10px 0 3px' }}>In progress <span style={hint}>(the stage's NPC; with no lines the quest shows but can't be chosen)</span></div>
                  <SpeechEditor value={stage.lines?.active} onChange={(v) => setStageLines('active', v)} placeholder="아직 부족하구먼." inQuest quests={quests} />
                  <div style={{ margin: '10px 0 3px' }}>Hand in <span style={hint}>(the stage's NPC, before Hand over; with no needs, the talk itself)</span></div>
                  <SpeechEditor value={stage.lines?.complete} onChange={(v) => setStageLines('complete', v)} placeholder="고맙네!" inQuest quests={quests} />
                  <div style={{ margin: '10px 0 3px' }}>After handing over <span style={hint}>(the stage's NPC, right after Hand over: where to go next, or to come back after the wait)</span></div>
                  <SpeechEditor value={stage.lines?.handed} onChange={(v) => setStageLines('handed', v)} placeholder="칼을 벼리는 데 하루는 걸리네. 내일 오게." inQuest quests={quests} />
                  <div style={{ ...hint, margin: '12px 0 0' }}>With a wait:</div>
                  <div style={{ margin: '4px 0 3px' }}>While waiting <span style={hint}>(the stage's NPC; with no lines the quest shows but can't be chosen)</span></div>
                  <SpeechEditor value={stage.lines?.waiting} onChange={(v) => setStageLines('waiting', v)} placeholder="아직 망치질 중일세." inQuest quests={quests} />
                  <div style={{ margin: '10px 0 3px' }}>When it's ready <span style={hint}>(the stage's NPC, before Take)</span></div>
                  <SpeechEditor value={stage.lines?.ready} onChange={(v) => setStageLines('ready', v)} placeholder="다 됐네! 받게." inQuest quests={quests} />
                </Section>
              )}

              <Section title="Supplies and rewards">
                <div style={{ marginBottom: 3 }}>
                  Given on accepting <span style={hint}>(once, when the quest starts, also when a graph reveals it; not taken back)</span>
                </div>
                <StackList wide fetchHeld={fetchHeld} stacks={quest.supplies || []} onChange={(v) => setQuestField('supplies', v)} />
                <div style={{ marginBottom: 3 }}>Rewards <span style={hint}>(when the last stage is done; item as /give writes it, [components] allowed)</span></div>
                <StackList wide fetchHeld={fetchHeld} stacks={quest.rewards} onChange={(v) => setQuestField('rewards', v)} />
              </Section>

              <QuestPlayers quest={quest} setMessage={setMessage} />

              <button onClick={deleteQuest} style={{ padding: '5px 10px', cursor: 'pointer', color: '#c0392b' }}>Delete quest</button>
            </>
          ) : folder ? (
            <FolderPanel
              folder={folder} folders={folders} setFolders={setFolders} items={quests} setItems={setQuests}
              onDeleted={(up) => setSelected(up ? { kind: 'folder', id: up } : null)}
            />
          ) : (
            <div style={{ color: '#888' }}>Choose a quest or folder on the left, or create one.</div>
          )}
        </div>
      </main>
    </div>
  )
}
