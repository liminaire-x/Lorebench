// Parts shared by the quest and NPC tabs.
import { useRef, useState } from 'react'

const input = { width: '100%', padding: '4px 6px', boxSizing: 'border-box' }

// One card per part of a quest or NPC. A new feature gets its own section.
export function Section({ title, children }) {
  return (
    <section style={{ border: '1px solid #e5e7eb', borderRadius: 8, padding: '10px 14px', marginBottom: 14 }}>
      <div style={{ fontWeight: 600, marginBottom: 8 }}>{title}</div>
      {children}
    </section>
  )
}

// A dialogue line is text, or { text, animation } when the NPC plays an animation
// as the line shows (DialogueLines.java). Without an animation it stays plain text.
// The animation is a name (played once), or { name, play: 'loop' } to repeat it while
// the page shows (0011).
export const lineText = (line) => (typeof line === 'string' ? line : line?.text ?? '')
const animationOf = (line) => (typeof line === 'string' ? '' : line?.animation ?? '')
export const lineAnimation = (line) => {
  const a = animationOf(line)
  return typeof a === 'string' ? a : a?.name ?? ''
}
export const lineLoops = (line) => animationOf(line)?.play === 'loop'
const makeLine = (text, animation, loop) => {
  if (animation.trim() === '') return text
  return { text, animation: loop ? { name: animation, play: 'loop' } : animation }
}

// Cues inside a line's text (Cues.java, 0014), each working from where it stands to the end
// of the page: [cue as inserted, where the value to type starts and ends in it, what it does].
const CUES = [
  ['<speed=0.5>', 7, 10, 'From here, this many times the usual typing speed (0.5 = slower, 2 = faster) until the page ends'],
  ['<pause=1>', 7, 8, 'Stop typing here for this many seconds (up to 10)'],
  ['<play=>', 6, 6, 'The NPC plays an animation here, once: type its name, like animation.chief.happy'],
  ['<loop=>', 6, 6, 'The NPC repeats an animation from here until the page turns: type its name'],
  ['<voice=none>', 12, 12, 'No voice sound from here (narration, a silent moment)'],
  ['<voice>', 7, 7, "The NPC's own voice again from here"],
]

// What an NPC says: one page per line, shown in this order. An empty list means
// nothing to say; the caller drops it from the document. The cue buttons write into the
// line that last had the cursor, at the cursor, with the value selected to type over.
export function LineList({ lines = [], onChange, placeholder }) {
  const areas = useRef([])
  const [at, setAt] = useState(null)
  const set = (i, text, animation, loop) => onChange(lines.map((l, j) => (j === i ? makeLine(text, animation, loop) : l)))
  const insertCue = ([cue, from, to]) => {
    const i = at !== null && at < lines.length ? at : lines.length - 1
    const area = areas.current[i]
    if (i < 0 || !area) return
    const text = lineText(lines[i])
    const start = area.selectionStart ?? text.length
    const end = area.selectionEnd ?? start
    set(i, text.slice(0, start) + cue + text.slice(end), lineAnimation(lines[i]), lineLoops(lines[i]))
    requestAnimationFrame(() => { area.focus(); area.setSelectionRange(start + from, start + to) })
  }
  return (
    <div>
      {lines.length > 0 && (
        <div style={{ display: 'flex', flexWrap: 'wrap', gap: 3, alignItems: 'center', marginBottom: 3, fontSize: 11, color: '#888' }}>
          <span title="A '<' before a letter starts a cue; each works until the page ends">Cues at the cursor:</span>
          {CUES.map((c) => (
            <button key={c[0]} title={c[3]} onMouseDown={(e) => e.preventDefault()} onClick={() => insertCue(c)}
              style={{ cursor: 'pointer', fontSize: 11, fontFamily: 'monospace', padding: '0 4px' }}>{c[0]}</button>
          ))}
        </div>
      )}
      {lines.map((line, i) => (
        <div key={i} style={{ display: 'flex', gap: 4, marginBottom: 3, alignItems: 'flex-start' }}>
          <span style={{ color: '#888', fontSize: 11, width: 16, paddingTop: 5 }}>{i + 1}</span>
          <textarea
            ref={(el) => { areas.current[i] = el }} onFocus={() => setAt(i)}
            value={lineText(line)} rows={1} placeholder={placeholder}
            onChange={(e) => set(i, e.target.value, lineAnimation(line), lineLoops(line))}
            style={{ ...input, flex: 1, resize: 'vertical', fontFamily: 'inherit' }}
          />
          <input
            value={lineAnimation(line)} placeholder="animation (optional)"
            title="Played by the NPC as this line shows, seen only by the player talking"
            onChange={(e) => set(i, lineText(line), e.target.value, lineLoops(line))}
            style={{ ...input, width: 170, fontFamily: 'monospace', fontSize: 11 }}
          />
          <label
            title="Repeat the animation while this line shows; it stops on the next line"
            style={{ fontSize: 11, color: lineAnimation(line) ? '#555' : '#bbb', paddingTop: 5, whiteSpace: 'nowrap' }}
          >
            <input
              type="checkbox" checked={lineLoops(line)} disabled={!lineAnimation(line)}
              onChange={(e) => set(i, lineText(line), lineAnimation(line), e.target.checked)}
            />
            Repeat
          </label>
          <button onClick={() => onChange(lines.filter((_, j) => j !== i))} style={{ cursor: 'pointer' }}>×</button>
        </div>
      ))}
      <button onClick={() => onChange(lines.concat(''))} style={{ cursor: 'pointer', color: '#2563eb' }}>+ line</button>
    </div>
  )
}

// What an NPC says in one place (Speech.java, 0012): lines, or cases of which the first
// whose "when" holds for the player is said (if / else if / otherwise). The last case
// without "when" is Otherwise. Plain lines are one Otherwise case, written as before.
export const isCases = (v) => Array.isArray(v) && v.some((x) => x !== null && typeof x === 'object' && 'lines' in x)

export const QUEST_STATES = [['hidden', 'not taken'], ['active', 'in progress'], ['waiting', 'waiting (handed in)'],
  ['ready', 'ready (to hand in, or to take after waiting)'], ['done', 'done']]

const box = { border: '1px solid #e5e7eb', borderRadius: 6, padding: '6px 8px', marginBottom: 6 }
const small = { cursor: 'pointer', fontSize: 11 }

// Back to plain lines when only Otherwise is left.
const tidyCases = (cases) => (cases.length === 1 && !cases[0].when ? cases[0].lines : cases)

export function SpeechEditor({ value = [], onChange, placeholder, inQuest, quests = [] }) {
  const newCase = () => ({ when: inQuest ? { timesDeclined: { min: 1 } } : { questState: { '': 'done' } }, lines: [] })
  if (!isCases(value)) {
    return (
      <div>
        <LineList lines={value} onChange={onChange} placeholder={placeholder} />
        <button
          onClick={() => onChange([newCase(), { lines: value }])} style={{ ...small, color: '#2563eb', marginTop: 3 }}
          title="Say something else when a condition holds; these lines become Otherwise"
        >+ case</button>
      </div>
    )
  }
  const cases = value
  const otherwise = cases.length > 0 && !cases[cases.length - 1].when
  const set = (next) => onChange(tidyCases(next))
  const move = (i, d) => {
    const next = cases.slice()
    ;[next[i], next[i + d]] = [next[i + d], next[i]]
    set(next)
  }
  const conditional = otherwise ? cases.length - 1 : cases.length
  return (
    <div>
      {cases.map((c, i) => (
        <div key={i} style={box}>
          <div style={{ display: 'flex', gap: 4, alignItems: 'center', marginBottom: 4 }}>
            <b style={{ fontSize: 12 }}>{!c.when ? 'Otherwise' : i === 0 ? 'If' : 'Else if'}</b>
            <span style={{ flex: 1 }} />
            {c.when && i > 0 && <button onClick={() => move(i, -1)} style={small} title="Check earlier">↑</button>}
            {c.when && i < conditional - 1 && <button onClick={() => move(i, 1)} style={small} title="Check later">↓</button>}
            <button onClick={() => set(cases.filter((_, j) => j !== i))} style={small} title="Remove this case">×</button>
          </div>
          {c.when && (
            <When
              when={c.when} inQuest={inQuest} quests={quests}
              onChange={(when) => set(cases.map((x, j) => (j === i ? { ...x, when } : x)))}
            />
          )}
          <LineList
            lines={c.lines} placeholder={placeholder}
            onChange={(lines) => set(cases.map((x, j) => (j === i ? { ...x, lines } : x)))}
          />
        </div>
      ))}
      <button
        onClick={() => set(otherwise ? [...cases.slice(0, -1), newCase(), cases[cases.length - 1]] : [...cases, newCase()])}
        style={{ ...small, color: '#2563eb' }}
      >+ case</button>
      {!otherwise && (
        <button onClick={() => set([...cases, { lines: [] }])} style={{ ...small, color: '#2563eb', marginLeft: 6 }}>+ otherwise</button>
      )}
    </div>
  )
}

// A case's conditions; all must hold. Counts are Minecraft ranges: 5, { min }, { max }.
function When({ when, onChange, inQuest, quests }) {
  const times = when.timesDeclined
  const min = typeof times === 'number' ? times : times?.min
  const max = typeof times === 'number' ? times : times?.max
  const setTimes = (lo, hi) => {
    const r = {}
    if (lo !== undefined) r.min = lo
    if (hi !== undefined) r.max = hi
    onChange({ ...when, timesDeclined: lo !== undefined && lo === hi ? lo : r })
  }
  const count = (s) => (s.trim() === '' || !/^\d+$/.test(s.trim()) ? undefined : parseInt(s, 10))
  const drop = (key) => {
    const { [key]: _, ...rest } = when
    onChange(rest)
  }
  const states = Object.entries(when.questState || {})
  const setStates = (entries) => {
    if (!entries.length) return drop('questState')
    onChange({ ...when, questState: Object.fromEntries(entries) })
  }
  const byTitle = [...quests].sort((a, b) => (a.title || '').localeCompare(b.title || ''))
  return (
    <div style={{ fontSize: 12, marginBottom: 4 }}>
      {times !== undefined && (
        <div style={{ display: 'flex', gap: 4, alignItems: 'center', marginBottom: 3 }}>
          Declined
          <input value={min ?? ''} placeholder="0" onChange={(e) => setTimes(count(e.target.value), max)} style={{ width: 44 }} />
          to
          <input value={max ?? ''} placeholder="any" onChange={(e) => setTimes(min, count(e.target.value))} style={{ width: 44 }} />
          times <span style={{ color: '#888' }}>(this quest; in After declining, this refusal counts)</span>
          <button onClick={() => drop('timesDeclined')} style={small}>×</button>
        </div>
      )}
      {states.map(([quest, state], i) => (
        <div key={i} style={{ display: 'flex', gap: 4, alignItems: 'center', marginBottom: 3 }}>
          Quest
          <select value={quest} onChange={(e) => setStates(states.map((s, j) => (j === i ? [e.target.value, s[1]] : s)))}>
            <option value="">(choose)</option>
            {byTitle.map((q) => <option key={q.id} value={q.id}>{q.title || q.id}</option>)}
          </select>
          is
          <select value={state} onChange={(e) => setStates(states.map((s, j) => (j === i ? [s[0], e.target.value] : s)))}>
            {QUEST_STATES.map(([v, label]) => <option key={v} value={v}>{label}</option>)}
          </select>
          <button onClick={() => setStates(states.filter((_, j) => j !== i))} style={small}>×</button>
        </div>
      ))}
      <select
        value="" style={{ fontSize: 11 }}
        onChange={(e) => {
          if (e.target.value === 'times') onChange({ ...when, timesDeclined: { min: 1 } })
          if (e.target.value === 'quest' && !states.some(([q]) => q === '')) setStates([...states, ['', 'done']])
        }}
      >
        <option value="">+ and…</option>
        {inQuest && times === undefined && <option value="times">times declined</option>}
        <option value="quest">quest state</option>
      </select>
    </div>
  )
}
