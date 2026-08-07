# Component guide (Logseq OG fork)

In-app tutorial for custom components in this build.  
Open from the app: **⋯ menu → Component guide**.

This document is the source of truth for **humans and AI agents** that write notes for this app.

| Component | Macro / UI | Status |
|-----------|------------|--------|
| Daily cards | `{{card ...}}` | active |
| Live query chrome | DSL `{{query ...}}` UI | active |
| Agenda timeline | `{{cronograma AAAA-MM-DD}}` | active |

---

## Daily cards — `{{card}}`

Visual, agent-agnostic cards for daily notes (or any page).

### Minimal examples

```markdown
{{card title=Estado, tone=ok, metric=12, body=Abiertas 12 · Cerradas 5 · Bloqueadas 0}}

{{card title=Foco, tone=watch, layout=wide, body=Informe trimestral · Revisión de diseño · Cierre 18:00}}

{{card Estado, ok, Atajo posicional · con chips}}
```

### Options

| Key | Values | Default |
|-----|--------|---------|
| `title` | text | — |
| `subtitle` | text | — |
| `tone` | `ok` · `watch` · `alert` · `info` · `neutral` | `neutral` |
| `layout` | `compact` · `default` · `wide` | `default` |
| `metric` | short number/text (large, right) | — |
| `icon` | Tabler icon name | from tone |
| `body` | text; if it has ` · ` or `|` with 2+ parts → **chips** | free args |
| `chips` | explicit chips, `|` separated (`a\|b\|c`) | — |

### Chips

- Prefer middle-dot in body: `body=Abiertas 12 · Cerradas 5`
- Or explicit: `chips=Abiertas 12|Cerradas 5`
- If the last token of a chip is a number, it is emphasized in the tone color.

### Notes for agents

- Do **not** put interactive TODOs inside cards; use `{{query}}` for tasks.
- Pure `{{card}}` blocks hide the Logseq bullet (class `is-daily-card`).
- Commas separate macro arguments. Keep commas out of unquoted values when possible.

More detail: `docs/daily-cards.md`.

---

## Live queries — `{{query}}`

Standard Logseq DSL queries with improved chrome in this fork:

1. **Header always visible** — you can see there is a live query.
2. **Chevron / “Live query”** — collapse or expand **results**.
3. **“Filtros”** — show/hide the **visual query builder** (AND / TODO / …).
4. **Preview line** when filters are hidden — click to open the builder (does not enter raw `{{query}}` edit mode).

### Example

```markdown
{{query (and (task TODO) [[foco-hoy]])}}
```

### Notes for agents

- Prefer interactive list results (not `query-table:: true`) when users need to mark TODO/DONE from the daily.
- Filters panel is the original Logseq builder UI, not raw EDN.

---

## Agenda timeline — `{{cronograma}}`

Renders the day's agenda as a visual timeline instead of flat bullets.

Unlike `{{card}}`, this macro takes **no options**: the data lives in the block's
own children, which stay ordinary editable Logseq blocks. Collapse the block and
you see the timeline; expand it and you get the raw markdown back.

### Structure

Macro block → one child per **day** → one grandchild per **event** → your own
notes below each event.

```markdown
- {{cronograma 2026-08-07}}
  collapsed:: true
	- 2026-08-07
		- **todo el día** Festivo local
		  calendario:: Trabajo
		- **08:00-09:30** Deporte
		  calendario:: Personal
		  ubicacion:: Gimnasio
		- **18:15-20:00** Clase de guitarra
		  calendario:: Personal
			- Llevar la partitura
	- 2026-08-08
		- **10:00-11:00** Revisión semanal
	- 2026-08-09
```

The macro argument is the day rendered in full detail (normally the journal's own
date). Every other day is shown as a compact one-line strip. A day block with no
children renders as "Sin eventos en las fuentes auditadas."

### Day blocks

| Form | Notes |
|------|-------|
| `2026-08-07` | **Preferred.** Unambiguous, and the Spanish label (`Viernes 7`) is derived from it |
| `[[Aug 7th, 2026]]` | Accepted, but only resolves if the title matches the graph's journal date format |

### Event blocks

| Part | Syntax | Notes |
|------|--------|-------|
| Time range | `**08:00-09:30**` | Leading, bold. Drives position and duration |
| Single time | `**18:15**` | No duration; gets a minimum row height |
| All day | `**todo el día**` | Rendered in a band above the hour scale |
| Title | rest of the line | Plain text |
| Metadata | `calendario::` · `ubicacion::` · `asistentes::` | Rendered as chips |
| State | `estado:: hecho` · `cancelado` · `movido` | Also settable by clicking the dot |
| Notes | child blocks | Collapsed behind a "N notas" toggle |

### How it reads

- **Bold** = still ahead. **Grey** = already past, or marked `hecho`/`cancelado`.
- The event in progress gets a ring; otherwise a thin "ahora HH:MM" line marks the
  current time. The clock ticks every 60 s, and only on the real current day.
- Gaps longer than 45 min collapse to a fixed height labelled `· 8 h ·`, so a day
  with a morning and an evening block still fits on one screen.

### Notes for agents

- Write `{{cronograma}}` with the **ISO date of the journal** as its only argument.
- Put the time first and in bold; everything after it is the title.
- Use `estado::` only for what you actually know. The clock already greys out the past.
- Don't nest a `{{query}}` inside the timeline: children are parsed as events.
- The older flat format (`**Viernes 7, 08:00-09:30** — Deporte. #Personal`) still renders,
  so old journals don't break — but write new ones in the structured form.

---

## How to open this guide in the app

1. Click **⋯** (more) in the top-right toolbar.
2. Choose **Component guide**.

---

## Extending this guide

When adding a new component:

1. Document it here (`docs/components.md`).
2. Add a section in `frontend.components.component-guide`.
3. Register the macro by requiring your namespace from `frontend.components.block`
   (next to `daily-card` / `cronograma`) — the `require` is the side effect.
4. Keep naming **agent-agnostic** (no product names tied to a specific AI).
5. Keep argument parsing in a `frontend.util.*` namespace with no `frontend.ui`
   dependency, so it can be unit-tested — the node test runner cannot load
   namespaces that pull in the DOM. See `frontend.util.daily-card` and
   `frontend.util.cronograma`.
