// Parts shared by the quest and NPC tabs.

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

// What an NPC says: one page per line, shown in this order. An empty list means
// nothing to say; the caller drops it from the document.
export function LineList({ lines = [], onChange, placeholder }) {
  const set = (i, text, animation, loop) => onChange(lines.map((l, j) => (j === i ? makeLine(text, animation, loop) : l)))
  return (
    <div>
      {lines.map((line, i) => (
        <div key={i} style={{ display: 'flex', gap: 4, marginBottom: 3, alignItems: 'flex-start' }}>
          <span style={{ color: '#888', fontSize: 11, width: 16, paddingTop: 5 }}>{i + 1}</span>
          <textarea
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
