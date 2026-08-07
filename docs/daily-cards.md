# Daily cards (`{{card}}`)

Generic visual **cards** you can put in a daily note (or any page).  
Anyone can write them: you, a script, or an AI agent. The app only **renders** them.

## Quick start

```markdown
{{card title=Estado, tone=ok, metric=12, body=Abiertas 12 · Cerradas 5 · Bloqueadas 0}}

{{card title=Foco, tone=watch, layout=wide, body=Informe trimestral · Revisión de diseño · Cierre 18:00}}

{{card title=Nota, tone=neutral, layout=compact, body=Solo un apunte}}
```

Positional shorthand (title, optional tone, rest = body):

```markdown
{{card Estado, ok, Abiertas 12 · Cerradas 5}}
```

## Options (v1)

| Key | Values | Default |
|-----|--------|---------|
| `title` | any text | — |
| `subtitle` | any text | — |
| `tone` | `ok` · `watch` · `alert` · `info` · `neutral` | `neutral` |
| `layout` | `compact` · `default` · `wide` | `default` |
| `metric` | short number/text (shown large) | — |
| `icon` | Tabler icon name (optional override) | from tone |
| `body` | main text; if it contains ` · ` or `|` with 2+ parts → chips | free args joined |
| `chips` | explicit chips, `|` separated (`a|b|c`) | — |

Unknown keys are ignored for now (reserved for later).

Every field is optional: `{{card title=Solo un título}}` is valid.

## Design notes

- **Not** a fixed dashboard — one reusable component you compose as you like.
- **Not** a task list: keep `{{query}}` for interactive TODOs.
- Commas separate macro arguments (Logseq parser). Prefer `key=value` for fields that might contain commas in `body`.
- Works in **desktop Electron** and **web** (`yarn watch` → http://localhost:3001).
- Body with middle-dot separators becomes **chips** (numbers at the end of a chip get emphasis).
- Pure `{{card}}` blocks get class `is-daily-card` on `.ls-block` so the Logseq bullet is hidden and the card reads as a panel.

## Example of a moldable daily

```markdown
- {{card title=Estado, tone=ok, metric=12, body=Abiertas 12 · Cerradas 5 · Bloqueadas 0}}
- {{card title=Foco, tone=watch, body=Informe trimestral · Cierre 18:00}}
- ## Agenda
	- {{cronograma 2026-08-07}}
- ## Tareas activas
	- {{query (task DOING)}}
```

Zero cards is fine. Ten cards is fine. The note stays moldable.

> Examples in this repo are deliberately generic. The repository is public —
> keep real names, health data and unpublished work out of documentation and
> out of the in-app guide, which ships inside every release build.

## Implementation

- Component: `src/main/frontend/components/daily_card.cljs`
- Argument parsing (pure, unit-tested): `src/main/frontend/util/daily_card.cljs`
- Styles: `src/main/frontend/components/daily_card.css`
- Registered via `frontend.components.macro/register` as `"card"`
- Required from `frontend.components.block` so it loads with the app
