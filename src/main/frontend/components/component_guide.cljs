(ns frontend.components.component-guide
  "In-app tutorial for custom components (cards, live queries, …).
   Agent-agnostic: for humans and AI authors of notes."
  (:require [frontend.state :as state]
            [frontend.ui :as ui]
            [frontend.version :refer [version]]
            [rum.core :as rum]))

(defn- code-block
  [s]
  [:pre.cp__component-guide-code
   [:code s]])

(defn- section
  [{:keys [id icon title kicker]} & children]
  (into
   [:article.cp__component-guide-section {:id id}
    [:header
     (when icon
       [:span.cp__component-guide-section__icon (ui/icon icon {:size 18})])
     [:div
      (when kicker
        [:div.cp__component-guide-section__kicker kicker])
      [:h2 title]]]]
   children))

(rum/defc panel
  [close-fn]
  [:section.cp__component-guide
   [:header.cp__component-guide__header
    [:div.cp__component-guide__eyebrow
     [:span.cp__component-guide__mark "OG"]
     [:span "COMPONENT GUIDE"]
     [:span.cp__component-guide__version (str "v" version)]]
    [:h1 "Componentes de esta build"]
    [:p "Cómo escribir y usar los bloques custom. Sirve para personas y para IAs que generan notas."]]

   [:div.cp__component-guide__body
    (section {:id "cards" :icon "layout-sidebar" :kicker "MACRO" :title "{{card}} — Daily cards"}
      [:p "Tarjetas visuales reutilizables (cuerpo, foco, agenda, lo que quieras). Agnósticas: las puede escribir cualquiera."]
      [:ul
       [:li [:strong "Tones:"] " ok · watch · alert · info · neutral"]
       [:li [:strong "Layouts:"] " compact · default · wide"]
       [:li [:strong "Chips:"] " si el body usa " [:code " · "] " (o " [:code "|"] "), se pinta en pills. Números al final se resaltan."]
       [:li [:strong "metric"] " sale grande a la derecha."]]
      (code-block
       "{{card title=Estado, tone=ok, metric=12, body=Abiertas 12 · Cerradas 5 · Bloqueadas 0}}

{{card title=Foco, tone=watch, layout=wide, body=Informe trimestral · Revisión de diseño · Cierre 18:00}}

{{card title=En curso, tone=neutral, metric=3, chips=Informe|Diseño|Contratos}}

{{card Estado, ok, Atajo posicional · con chips}}")
      [:p.cp__component-guide-hint
       "Docs largas: " [:code "docs/daily-cards.md"] " y " [:code "docs/components.md"] "."])

    (section {:id "queries" :icon "search" :kicker "UI" :title "{{query}} — Live queries"}
      [:p "Consultas DSL de Logseq con chrome mejorado en esta build:"]
      [:ul
       [:li [:strong "Live query"] " + chevron → muestra/oculta resultados"]
       [:li [:strong "Filtros"] " → constructor visual (AND / TODO / …)"]
       [:li "Con filtros cerrados, una " [:strong "línea preview"] " abre el builder (sin entrar en edición cruda del macro)"]]
      (code-block
       "{{query (and (task TODO) [[foco-hoy]])}}

{{query (task DOING)}}")
      [:p.cp__component-guide-hint
       "Para tareas que se marcan desde la diaria, evita " [:code "query-table:: true"] " (mejor lista de bloques)."])

    (section {:id "cronograma" :icon "calendar-time" :kicker "MACRO" :title "{{cronograma}} — Línea de tiempo"}
      [:p "Pinta la agenda del día como cronograma. Los eventos son "
       [:strong "bloques hijos normales"] ": despliega el bloque y editas markdown de siempre."]
      [:ul
       [:li [:strong "Estructura:"] " macro → hijo por día (fecha ISO) → nieto por evento → notas tuyas."]
       [:li [:strong "Hora:"] " al principio del evento, en negrita — " [:code "**HH:MM-HH:MM**"] " o " [:code "**todo el día**"] "."]
       [:li [:strong "Estado:"] " lo pasado sale en gris y lo pendiente en negrita. Clic en el punto cicla "
        [:code "estado::"] " hecho → cancelado → sin marca."]
       [:li [:strong "Propiedades:"] " " [:code "calendario::"] " · " [:code "ubicacion::"] " · " [:code "asistentes::"] " salen como chips."]
       [:li "Los huecos de más de 45 min se comprimen (" [:code "· 8 h ·"] ") para que el día quepa en una pantalla."]]
      (code-block
       "{{cronograma 2026-08-07}}
collapsed:: true
\t- 2026-08-07
\t\t- **todo el día** Festivo local
\t\t  calendario:: Trabajo
\t\t- **08:00-09:30** Deporte
\t\t  calendario:: Personal
\t\t  ubicacion:: Gimnasio
\t\t- **18:15-20:00** Clase de guitarra
\t\t\t- Llevar la partitura
\t- 2026-08-08
\t\t- **10:00-11:00** Revisión semanal")
      [:p.cp__component-guide-hint
       "El argumento es el día que se pinta con detalle; los demás van en tira compacta. "
       "Con " [:code "collapsed:: true"] " solo se ve el cronograma; al desplegar, los bloques crudos."])

    (section {:id "agents" :icon "terminal-2" :kicker "AUTORES" :title "Notas para IAs y scripts"}
      [:ul
       [:li "El software solo " [:strong "renderiza"] ". El contenido lo escribe un humano, un script o un agente."]
       [:li "No inventes macros nuevas: usa " [:code "{{card}}"] ", " [:code "{{query}}"] " y "
        [:code "{{cronograma}}"] " documentados aquí."]
       [:li "Mantén TODOs interactivos en queries; usa cards para " [:strong "resumen / estado / lectura"] "."]
       [:li "Fuente de verdad versionada en el repo: " [:code "docs/components.md"] "."]])]

   [:footer.cp__component-guide__footer
    [:span "Extiende este guide al añadir componentes nuevos."]
    (ui/button "Cerrar"
               :class "ui__modal-enter"
               :on-click close-fn)]])

(defn open!
  []
  (state/set-modal! panel
                    {:id "component-guide"
                     :close-btn? true
                     :close-backdrop? true
                     :center? true
                     :label "component-guide"}))
