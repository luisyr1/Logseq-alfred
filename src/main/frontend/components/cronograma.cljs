(ns frontend.components.cronograma
  "The `{{cronograma AAAA-MM-DD}}` macro: renders the child blocks of the macro
  block as a visual timeline instead of plain bullets.

  Nothing is stored outside markdown — the children *are* the data, so the block
  stays fully editable by expanding it. All parsing/layout lives in
  `frontend.util.cronograma`; this namespace only wires it to the DB and paints."
  (:require [clojure.string :as string]
            [frontend.components.macro :as component-macro]
            [frontend.date :as date]
            [frontend.db :as db]
            [frontend.db-mixins :as db-mixins]
            [frontend.db.model :as db-model]
            [frontend.db.react :as react]
            [frontend.handler.editor :as editor-handler]
            [frontend.handler.editor.property :as editor-property]
            [frontend.state :as state]
            [frontend.ui :as ui]
            [frontend.util :as util :refer [react]]
            [frontend.util.cronograma :as c]
            [rum.core :as rum]))

;; ---------------------------------------------------------------------------
;; DB access

(defn- ->plano
  "Datascript entity -> plain map, `depth` levels of children deep.
  The parser only wants data, and plain maps keep it testable."
  [entity depth]
  {:block/uuid (:block/uuid entity)
   :block/content (:block/content entity)
   :block/properties (:block/properties entity)
   :block/properties-text-values (:block/properties-text-values entity)
   :block/children (when (pos? depth)
                     (mapv #(->plano % (dec depth))
                           (db-model/sort-by-left (:block/_parent entity) entity)))})

(defn- sub-hijos
  "Immediate children of the macro block, two levels deep, re-running whenever
  any descendant changes (see ::alfred-block-children in frontend.db.react)."
  [db-id block-uuid]
  (when-let [repo (state/get-current-repo)]
    (when (and db-id block-uuid)
      (-> (react/q repo [:frontend.db.react/alfred-block-children db-id]
                   {:query-fn (fn [_]
                                (mapv #(->plano % 2)
                                      (db-model/get-block-immediate-children repo block-uuid)))}
                   nil)
          react))))

(defn- titulo->journal-day
  "Resolve a day label to a yyyyMMdd int. Prefers the page that already exists
  in the graph (its `:block/journal-day` is authoritative) and falls back to
  parsing the title with the user's date format."
  [titulo]
  (when-not (string/blank? titulo)
    (let [titulo (string/trim titulo)]
      (or (:block/journal-day (db/entity [:block/name (util/page-name-sanity-lc titulo)]))
          (try (date/journal-title->int titulo) (catch :default _ nil))
          (let [[_ y m d] (re-find #"(\d{4})-(\d{1,2})-(\d{1,2})" titulo)]
            (when y (+ (* 10000 (js/parseInt y 10))
                       (* 100 (js/parseInt m 10))
                       (js/parseInt d 10))))))))

;; ---------------------------------------------------------------------------
;; Interaction guards
;;
;; `.block-content` turns any mouse-down into "edit this block", so every piece
;; of chrome has to stop the event — same trick the live-query header uses.

(defn- stop-edit!
  [e]
  (util/stop e))

(defn- ciclo-estado!
  "Untouched -> hecho -> cancelado -> untouched."
  [uuid estado]
  (case estado
    nil (editor-property/set-block-property! uuid "estado" "hecho")
    :hecho (editor-property/set-block-property! uuid "estado" "cancelado")
    (editor-property/remove-block-property! uuid "estado")))

;; ---------------------------------------------------------------------------
;; Pieces

(rum/defc chip
  [k v]
  [:span.alfred-cronograma-chip
   [:span.alfred-cronograma-chip-k (name k)]
   [:span.alfred-cronograma-chip-v v]])

(rum/defc rango-texto
  [{:keys [inicio fin todo-el-dia?]}]
  (cond
    todo-el-dia? [:span "todo el día"]
    (and inicio fin) [:span (str (c/minutos->hhmm inicio) "–" (c/minutos->hhmm fin))
                      [:span.alfred-cronograma-dur
                       (str " · " (c/formatea-duracion (- fin inicio)))]]
    inicio [:span (c/minutos->hhmm inicio)]
    :else nil))

(rum/defcs fila-evento < rum/reactive (rum/local false ::notas-abiertas)
  [state {:keys [uuid titulo inicio estado chips notas] :as evento} {:keys [alto pasado? en-curso? editable?]}]
  (let [*notas (::notas-abiertas state)
        notas-abiertas? (rum/react *notas)]
    [:div.alfred-cronograma-fila
     {:class (string/join " " (cond-> []
                                pasado? (conj "es-pasado")
                                en-curso? (conj "es-ahora")
                                (= :cancelado estado) (conj "es-cancelado")
                                (= :movido estado) (conj "es-movido")))
      :style {:min-height (str alto "px")}}

     [:div.alfred-cronograma-hora (or (c/minutos->hhmm inicio) "")]

     [:a.alfred-cronograma-punto
      {:title (case estado
                :hecho "Hecho — clic para marcar cancelado"
                :cancelado "Cancelado — clic para quitar la marca"
                :movido "Movido — clic para quitar la marca"
                "Clic para marcar como hecho")
       :on-mouse-down stop-edit!
       :on-click (fn [e]
                   (stop-edit! e)
                   (when (and editable? uuid) (ciclo-estado! uuid estado)))}
      (case estado
        :hecho (ui/icon "check" {:size 11})
        :cancelado (ui/icon "x" {:size 11})
        :movido (ui/icon "arrow-right" {:size 11})
        [:span.alfred-cronograma-punto-dot])]

     [:div.alfred-cronograma-cuerpo
      [:div.alfred-cronograma-titulo titulo]
      [:div.alfred-cronograma-meta
       (rango-texto evento)
       (for [[k v] chips]
         (rum/with-key (chip k v) (str uuid "-" k)))]
      (when (seq notas)
        [:a.alfred-cronograma-notas-btn
         {:on-mouse-down stop-edit!
          :on-click (fn [e] (stop-edit! e) (swap! *notas not))}
         (ui/icon (if notas-abiertas? "chevron-down" "chevron-right") {:size 12})
         [:span (str (count notas) (if (= 1 (count notas)) " nota" " notas"))]])
      (when (and notas-abiertas? (seq notas))
        [:div.alfred-cronograma-notas
         (for [n notas]
           [:div.alfred-cronograma-nota {:key (str (:block/uuid n))}
            (c/primera-linea (:block/content n))])])]]))

(rum/defc franja-todo-el-dia
  [eventos {:keys [pasado? editable?]}]
  (when (seq eventos)
    [:div.alfred-cronograma-allday
     (for [e eventos]
       (rum/with-key
         (fila-evento e {:alto 34 :pasado? pasado? :en-curso? false :editable? editable?})
         (str (:uuid e))))]))

(rum/defc segmento-hueco
  [{:keys [minutos comprimido? alto]}]
  [:div.alfred-cronograma-hueco
   {:class (when comprimido? "es-comprimido")
    :style {:height (str alto "px")}}
   (when comprimido?
     [:span.alfred-cronograma-hueco-label (str "· " (c/formatea-duracion minutos) " ·")])])

(rum/defc marcador-ahora
  [minutos]
  [:div.alfred-cronograma-ahora
   [:span.alfred-cronograma-ahora-hora (c/minutos->hhmm minutos)]
   [:span.alfred-cronograma-ahora-linea]])

(rum/defc dia-detallado
  [{:keys [journal-day etiqueta todo-el-dia eventos]} {:keys [ahora dia-pasado? editable?]}]
  [:div.alfred-cronograma-dia
   [:div.alfred-cronograma-dia-cab
    [:span.alfred-cronograma-dia-nombre etiqueta]
    (when-let [mes (c/nombre-mes journal-day)]
      [:span.alfred-cronograma-dia-mes mes])]

   (franja-todo-el-dia todo-el-dia {:pasado? dia-pasado? :editable? editable?})

   (if (empty? eventos)
     [:div.alfred-cronograma-vacio "Sin eventos en las fuentes auditadas."]
     [:div.alfred-cronograma-pista
      (for [[i seg] (map-indexed vector (c/inserta-ahora (c/layout eventos) ahora))]
        (case (:tipo seg)
          :hueco (rum/with-key (segmento-hueco seg) (str "h" i))
          :ahora (rum/with-key (marcador-ahora (:minutos seg)) (str "n" i))
          :evento (let [e (:evento seg)]
                    (rum/with-key
                      (fila-evento e {:alto (:alto seg)
                                      :pasado? (or dia-pasado? (c/pasado? e ahora))
                                      :en-curso? (c/en-curso? e ahora)
                                      :editable? editable?})
                      (str (:uuid e) "-" i)))
          nil))])])

(rum/defc dia-compacto
  [{:keys [etiqueta-corta todo-el-dia eventos]}]
  [:div.alfred-cronograma-compacto
   [:span.alfred-cronograma-compacto-dia etiqueta-corta]
   (if (and (empty? eventos) (empty? todo-el-dia))
     [:span.alfred-cronograma-compacto-vacio "Sin eventos en las fuentes auditadas."]
     [:span.alfred-cronograma-compacto-lista
      (for [e (concat todo-el-dia eventos)]
        [:span.alfred-cronograma-compacto-item {:key (str (:uuid e))}
         [:span.alfred-cronograma-compacto-hora
          (if (:todo-el-dia? e) "—" (c/minutos->hhmm (:inicio e)))]
         [:span.alfred-cronograma-compacto-titulo (:titulo e)]])])])

;; ---------------------------------------------------------------------------
;; Root

(rum/defcs cronograma-cp < rum/reactive db-mixins/query
  (rum/local 0 ::tick)
  {:did-mount (fn [state]
                (let [*tick (::tick state)]
                  (assoc state ::timer (js/setInterval #(swap! *tick inc) 60000))))
   :will-unmount (fn [state]
                   (some-> (::timer state) js/clearInterval)
                   (dissoc state ::timer))}
  [state config options]
  ;; subscribe to the minute tick so "ahora" moves without a manual refresh
  (rum/react (::tick state))
  (let [ancla-arg (some-> (first (:arguments options)) string/trim)
        block (:block config)
        db-id (:db/id block)
        block-uuid (:block/uuid block)
        editable? (not (:preview? config))
        hijos (sub-hijos db-id block-uuid)
        dias (c/parse-cronograma (or hijos []) {:ancla ancla-arg
                                                :resolver titulo->journal-day})
        hoy (c/hoy-journal-day)
        ancla-day (or (titulo->journal-day ancla-arg) hoy)
        ;; the detailed day is the anchor if we have it, otherwise the first one
        principal (or (first (filter #(= ancla-day (:journal-day %)) dias))
                      (first dias))
        resto (remove #(= (:journal-day %) (:journal-day principal)) dias)
        dia-de-hoy? (= (:journal-day principal) hoy)
        ahora (when dia-de-hoy? (c/ahora-en-minutos))
        dia-pasado? (and (:journal-day principal) (< (:journal-day principal) hoy))
        total (reduce + (map #(+ (count (:eventos %)) (count (:todo-el-dia %))) dias))
        pendientes (->> (:eventos principal)
                        (remove #(c/pasado? % ahora))
                        count)]
    [:div.alfred-cronograma.forbid-edit
     {:on-mouse-down stop-edit!}
     [:div.alfred-cronograma-cab
      [:a.alfred-cronograma-cab-titulo
       {:title "Desplegar el bloque para editar los eventos como texto"
        :on-mouse-down stop-edit!
        :on-click (fn [e]
                    (stop-edit! e)
                    (when block-uuid (editor-handler/expand-block! block-uuid)))}
       (ui/icon "calendar-time" {:size 15})
       [:span.font-medium (str "Cronograma" (when-let [n (:etiqueta principal)] (str " · " n)))]]
      [:span.alfred-cronograma-cab-meta
       (cond
         (zero? total) "sin eventos"
         ahora (str pendientes " por delante · ahora " (c/minutos->hhmm ahora))
         :else (str total (if (= 1 total) " evento" " eventos")))]]

     (if (zero? total)
       [:div.alfred-cronograma-vacio
        "Sin eventos. Despliega el bloque para escribirlos."]
       [:<>
        (when principal
          (dia-detallado principal {:ahora ahora
                                    :dia-pasado? dia-pasado?
                                    :editable? editable?}))
        (when (seq resto)
          [:div.alfred-cronograma-resto
           (for [d resto]
             (rum/with-key (dia-compacto d) (str (:journal-day d))))])])]))

(component-macro/register "cronograma" cronograma-cp)
