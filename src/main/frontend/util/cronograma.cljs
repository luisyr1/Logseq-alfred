(ns frontend.util.cronograma
  "Pure parsing and layout helpers for the `{{cronograma}}` macro.

  Kept free of DB/state deps so the whole thing is unit-testable: the component
  in `frontend.components.cronograma` only feeds it plain maps.

  The markdown Alfred writes looks like:

      - {{cronograma 2026-08-07}}
        collapsed:: true
      \t- [[Viernes 7 de Agosto, 2026]]
      \t\t- **07:35-07:55** Llevar el coche al taller
      \t\t  calendario:: Personal

  but the parser is deliberately forgiving so the older flat format
  (`**Viernes 7, 08:00-09:30** — Deporte. #Personal`) still renders."
  (:require [clojure.string :as string]))

;; ---------------------------------------------------------------------------
;; Regexes

(def ^:private property-line-re #"^\s*[^\s:]+::")
(def ^:private bold-re #"^\s*\*\*(.+?)\*\*\s*(.*)$")
(def ^:private rango-re #"(\d{1,2})[:.h](\d{2})\s*(?:-|–|—|hasta)\s*(\d{1,2})[:.h](\d{2})")
(def ^:private hora-re #"(\d{1,2})[:.h](\d{2})")
(def ^:private todo-el-dia-re #"(?i)todo el d[ÍíIi]a|all[ -]?day")
(def ^:private tag-re #"#([A-Za-z0-9_/áéíóúüñÁÉÍÓÚÜÑ-]+)")
(def ^:private page-ref-re #"\[\[(.+?)\]\]")
(def ^:private iso-re #"(\d{4})-(\d{1,2})-(\d{1,2})")
(def ^:private basura-inicial-re #"^[\s—–\-,:;•·]+")

;; Properties that carry no meaning for the reader.
(def ^:private propiedades-ocultas
  #{:id :collapsed :estado :heading :query-table
    :logseq.macro-name :logseq.macro-arguments :logseq.order-list-type})

;; Shown first, in this order, when present.
(def ^:private propiedades-destacadas [:calendario :ubicacion :ubicación :asistentes])

;; ---------------------------------------------------------------------------
;; Dates

(def dias-semana ["Domingo" "Lunes" "Martes" "Miércoles" "Jueves" "Viernes" "Sábado"])
(def dias-semana-corto ["Dom" "Lun" "Mar" "Mié" "Jue" "Vie" "Sáb"])
(def meses ["Enero" "Febrero" "Marzo" "Abril" "Mayo" "Junio"
            "Julio" "Agosto" "Septiembre" "Octubre" "Noviembre" "Diciembre"])

(defn journal-day->date
  "20260807 -> a local js/Date at midnight. nil-safe."
  [journal-day]
  (when (and journal-day (>= journal-day 10000000))
    (let [y (quot journal-day 10000)
          m (mod (quot journal-day 100) 100)
          d (mod journal-day 100)]
      (js/Date. y (dec m) d))))

(defn date->journal-day
  [^js/Date date]
  (when date
    (+ (* 10000 (.getFullYear date))
       (* 100 (inc (.getMonth date)))
       (.getDate date))))

(defn hoy-journal-day
  []
  (date->journal-day (js/Date.)))

(defn ahora-en-minutos
  "Minutes since local midnight."
  []
  (let [d (js/Date.)]
    (+ (* 60 (.getHours d)) (.getMinutes d))))

(defn nombre-dia
  "\"Viernes 7\" — the label used for the detailed day."
  [journal-day]
  (when-let [d (journal-day->date journal-day)]
    (str (nth dias-semana (.getDay d)) " " (.getDate d))))

(defn nombre-dia-corto
  "\"Sáb 8\" — the label used in the compact strip."
  [journal-day]
  (when-let [d (journal-day->date journal-day)]
    (str (nth dias-semana-corto (.getDay d)) " " (.getDate d))))

(defn nombre-mes
  [journal-day]
  (when-let [d (journal-day->date journal-day)]
    (nth meses (.getMonth d))))

;; ---------------------------------------------------------------------------
;; Time helpers

(defn- ->int
  [s]
  (when s
    (let [n (js/parseInt s 10)]
      (when-not (js/isNaN n) n))))

(defn- hm->minutos
  [h m]
  (when-let [h' (->int h)]
    (when-let [m' (->int m)]
      (when (and (< h' 24) (< m' 60))
        (+ (* 60 h') m')))))

(defn minutos->hhmm
  [minutos]
  (when minutos
    (let [h (quot minutos 60)
          m (mod minutos 60)]
      (str (when (< h 10) "0") h ":" (when (< m 10) "0") m))))

(defn formatea-duracion
  "90 -> \"1 h 30\", 45 -> \"45 min\", 480 -> \"8 h\"."
  [minutos]
  (when (and minutos (pos? minutos))
    (let [h (quot minutos 60)
          m (mod minutos 60)]
      (cond
        (zero? h) (str m " min")
        (zero? m) (str h " h")
        :else (str h " h " m)))))

;; ---------------------------------------------------------------------------
;; Block content

(defn primera-linea
  "The title line of a block: first non-blank line that isn't a `key:: value`."
  [content]
  (->> (string/split-lines (or content ""))
       (remove #(re-find property-line-re %))
       (remove string/blank?)
       first))

(defn- normaliza-estado
  [v]
  (let [s (string/lower-case (str (if (coll? v) (first v) v)))]
    (cond
      (string/blank? s) nil
      (re-find #"cancel" s) :cancelado
      (re-find #"movid|moved|aplaz|pospue" s) :movido
      (re-find #"hech|done|list|✅|ok" s) :hecho
      :else nil)))

(defn- valor-propiedad
  "Property values can be parsed into ref sets; fall back to the raw text."
  [k v text-values]
  (let [texto (get text-values k)]
    (cond
      (and (coll? v) (seq texto)) texto
      (coll? v) (string/join ", " (map str v))
      :else (str v))))

(defn- chips-propiedades
  [properties text-values]
  (let [visibles (remove (fn [[k _]] (contains? propiedades-ocultas k)) properties)
        destacadas (keep (fn [k] (when-let [v (get properties k)]
                                   [k (valor-propiedad k v text-values)]))
                         propiedades-destacadas)
        resto (keep (fn [[k v]]
                      (when-not (some #{k} propiedades-destacadas)
                        [k (valor-propiedad k v text-values)]))
                    visibles)]
    (->> (concat destacadas resto)
         (remove (fn [[_ v]] (string/blank? v)))
         vec)))

(defn parse-evento
  "Turn an event block into a plain map. Never throws; unparseable lines simply
  come back with `:inicio` nil and render as a plain row."
  [block]
  (let [content (:block/content block)
        properties (:block/properties block)
        linea (or (primera-linea content) "")
        [_ negrita resto] (re-find bold-re linea)
        meta (or negrita linea)
        cuerpo (if negrita (or resto "") linea)
        todo-el-dia? (boolean (re-find todo-el-dia-re meta))
        [_ h1 m1 h2 m2] (re-find rango-re meta)
        inicio (if h1
                 (hm->minutos h1 m1)
                 (let [[_ h m] (re-find hora-re meta)]
                   (hm->minutos h m)))
        fin (when h2 (hm->minutos h2 m2))
        tags (mapv second (re-seq tag-re cuerpo))
        titulo (-> cuerpo
                   (string/replace rango-re "")
                   (string/replace hora-re "")
                   (string/replace todo-el-dia-re "")
                   (string/replace tag-re "")
                   (string/replace basura-inicial-re "")
                   string/trim)]
    {:uuid (:block/uuid block)
     :titulo (if (string/blank? titulo) (string/trim linea) titulo)
     :inicio (when-not todo-el-dia? inicio)
     :fin (when (and fin inicio (> fin inicio)) fin)
     :todo-el-dia? (or todo-el-dia? (nil? inicio))
     :estado (normaliza-estado (:estado properties))
     :tags tags
     :chips (chips-propiedades properties (:block/properties-text-values block))
     :notas (vec (:block/children block))}))

(defn parse-dia
  "Turn a day block (`[[Viernes 7 de Agosto, 2026]]`) plus its children into a map.

  `resolver` maps a page title to a journal-day int; pass
  `frontend.date/journal-title->int` in the app and a stub in tests."
  [block resolver]
  (let [linea (or (primera-linea (:block/content block)) "")
        [_ titulo-ref] (re-find page-ref-re linea)
        [_ y m d] (re-find iso-re linea)
        journal-day (or (when titulo-ref (resolver titulo-ref))
                        (when y (+ (* 10000 (->int y)) (* 100 (->int m)) (->int d)))
                        (resolver (string/trim linea)))
        eventos (->> (:block/children block)
                     (map parse-evento)
                     (remove #(string/blank? (:titulo %))))]
    {:uuid (:block/uuid block)
     :journal-day journal-day
     :etiqueta (or (nombre-dia journal-day) (string/trim linea))
     :etiqueta-corta (or (nombre-dia-corto journal-day) (string/trim linea))
     :todo-el-dia (->> eventos (filter :todo-el-dia?) vec)
     :eventos (->> eventos
                   (remove :todo-el-dia?)
                   (sort-by (juxt :inicio :titulo))
                   vec)}))

(defn parse-cronograma
  "Children of the `{{cronograma}}` block. Accepts either day blocks with event
  grandchildren, or a flat list of events (legacy) which lands in a single
  anchor day."
  [hijos {:keys [ancla resolver]}]
  (let [dia? (fn [b] (let [l (or (primera-linea (:block/content b)) "")]
                       (or (some? (re-find page-ref-re l))
                           (some? (re-find iso-re l)))))]
    (if (some dia? hijos)
      (->> hijos
           (filter dia?)
           (map #(parse-dia % resolver))
           (sort-by #(or (:journal-day %) 0))
           vec)
      (let [day (or (resolver (or ancla "")) (hoy-journal-day))]
        [(-> (parse-dia {:block/children hijos} resolver)
             (assoc :journal-day day
                    :etiqueta (or (nombre-dia day) "")
                    :etiqueta-corta (or (nombre-dia-corto day) "")))]))))

;; ---------------------------------------------------------------------------
;; Elastic layout
;;
;; Position is proportional to the clock so the shape of the day is readable,
;; but any gap longer than `hueco-umbral` collapses to a fixed height with a
;; "· 8 h ·" label — otherwise a day with one morning and one evening event
;; would be three screens of nothing.

(def ^:private px-por-min 0.85)
(def ^:private alto-min-evento 46)
(def ^:private alto-max-evento 130)
(def ^:private hueco-umbral 45)
(def ^:private alto-hueco-comprimido 26)
(def ^:private alto-hueco-min 6)

(defn- redondea [n] (js/Math.round n))

(defn alto-evento
  [{:keys [inicio fin]}]
  (if (and inicio fin (> fin inicio))
    (-> (* (- fin inicio) px-por-min)
        (max alto-min-evento)
        (min alto-max-evento)
        redondea)
    alto-min-evento))

(defn- fin-efectivo
  [{:keys [inicio fin]}]
  (or fin inicio))

(defn layout
  "Timed events (already sorted) -> segments to paint."
  [eventos]
  (loop [[e & mas] eventos
         previo nil
         acc []]
    (if (nil? e)
      acc
      (let [hueco (when-let [antes (and previo (fin-efectivo previo))]
                    (when (:inicio e) (- (:inicio e) antes)))
            seg-hueco (when (and hueco (pos? hueco))
                        (if (> hueco hueco-umbral)
                          {:tipo :hueco :minutos hueco :comprimido? true
                           :alto alto-hueco-comprimido}
                          {:tipo :hueco :minutos hueco :comprimido? false
                           :alto (max alto-hueco-min (redondea (* hueco px-por-min)))}))]
        (recur mas e (cond-> acc
                       seg-hueco (conj seg-hueco)
                       :always (conj {:tipo :evento :evento e :alto (alto-evento e)})))))))

(defn en-curso?
  [{:keys [inicio fin]} ahora]
  (boolean (and ahora inicio
                (>= ahora inicio)
                (< ahora (or fin (+ inicio 30))))))

(defn pasado?
  [{:keys [inicio fin estado] :as evento} ahora]
  (boolean (or (contains? #{:hecho :cancelado} estado)
               (and ahora inicio (>= ahora (or fin (+ inicio 30)))
                    (not (en-curso? evento ahora))))))

(defn inserta-ahora
  "Splice a `:ahora` marker into the segments, unless an event is already in
  progress (that one gets highlighted instead). `ahora` is nil for any day that
  isn't today, and then nothing is inserted."
  [segmentos ahora]
  (if (nil? ahora)
    segmentos
    (let [eventos (keep :evento segmentos)]
      (if (some #(en-curso? % ahora) eventos)
        segmentos
        (let [marcador {:tipo :ahora :minutos ahora :alto 0}
              idx (->> (map-indexed vector segmentos)
                       (some (fn [[i s]]
                               (when (and (= :evento (:tipo s))
                                          (:inicio (:evento s))
                                          (> (:inicio (:evento s)) ahora))
                                 i))))]
          (cond
            (nil? idx) (conj (vec segmentos) marcador)
            (zero? idx) (into [marcador] segmentos)
            ;; drop the gap segment right before the event and keep the marker there
            :else (let [antes (subvec (vec segmentos) 0 idx)
                        despues (subvec (vec segmentos) idx)]
                    (into (conj antes marcador) despues))))))))
