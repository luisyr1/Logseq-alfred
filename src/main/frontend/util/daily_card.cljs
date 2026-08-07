(ns frontend.util.daily-card
  "Pure parsing helpers for the `{{card}}` macro.

  Split out of `frontend.components.daily-card` so the argument parsing can be
  unit-tested: the component namespace pulls in `frontend.ui`, which needs a DOM
  and therefore cannot load in the node test runner."
  (:require [clojure.string :as string]))

(def known-tones #{"ok" "watch" "alert" "info" "neutral"})
(def known-layouts #{"compact" "default" "wide"})

(def known-keys
  #{"title" "tone" "layout" "icon" "metric" "body" "subtitle" "chips"})

(defn strip-quotes
  [s]
  (let [s (string/trim (str s))]
    (cond
      (and (>= (count s) 2)
           (or (and (string/starts-with? s "\"") (string/ends-with? s "\""))
               (and (string/starts-with? s "'") (string/ends-with? s "'"))))
      (subs s 1 (dec (count s)))

      :else s)))

(defn parse-kv
  "Parse 'key=value' or 'key: value'. Returns [k v] or nil."
  [s]
  (let [s (string/trim (str s))]
    (when-let [[_ k v] (re-matches #"(?i)([a-z][a-z0-9_-]*)\s*[=:]\s*(.+)" s)]
      [(string/lower-case k) (strip-quotes v)])))

(defn split-chips
  "Split a chips string on | or middle-dot. Returns nil if fewer than 2 parts."
  [s]
  (when (and (string? s) (not (string/blank? s)))
    (let [parts (->> (string/split s #"\s*[·|]\s*")
                     (map string/trim)
                     (remove string/blank?)
                     vec)]
      (when (>= (count parts) 2)
        parts))))

(defn parse-card-args
  "Turn macro argument list into a card options map."
  [arguments]
  (let [args (->> (or arguments [])
                  (map str)
                  (map string/trim)
                  (remove string/blank?))
        kv-pairs (keep parse-kv args)
        kv-map (into {} kv-pairs)
        free (->> args
                  (remove parse-kv)
                  vec)
        title-from-kv (get kv-map "title")
        title (or title-from-kv (first free))
        tone-candidate (or (get kv-map "tone")
                           (when (and (second free)
                                      (contains? known-tones (string/lower-case (str (second free)))))
                             (string/lower-case (str (second free)))))
        tone (let [t (some-> tone-candidate str string/lower-case)]
               (if (contains? known-tones t) t "neutral"))
        layout (let [l (some-> (get kv-map "layout") str string/lower-case)]
                 (if (contains? known-layouts l) l "default"))
        body-from-kv (get kv-map "body")
        free-for-body (cond
                        body-from-kv []

                        ;; positional "title, tone, rest…" — drop both
                        (and (second free)
                             (contains? known-tones (string/lower-case (str (second free)))))
                        (subvec free 2)

                        ;; the title was taken from the first free argument — drop it.
                        ;; When it came from `title=`, every free argument is body.
                        (and (seq free) (nil? title-from-kv))
                        (subvec free 1)

                        :else free)
        body (or body-from-kv
                 (when (seq free-for-body)
                   (string/join ", " free-for-body)))
        ;; Prefer explicit chips=; else derive from body separators
        chips (or (split-chips (get kv-map "chips"))
                  (split-chips body))]
    {:title (some-> title str string/trim)
     :subtitle (some-> (get kv-map "subtitle") str string/trim)
     :tone tone
     :layout layout
     :icon (some-> (get kv-map "icon") str string/trim)
     :metric (some-> (get kv-map "metric") str string/trim)
     :body (some-> body str string/trim)
     :chips chips
     :extra (into {} (remove (fn [[k _]] (contains? known-keys k)) kv-map))}))

(defn tone-icon
  [tone]
  (case tone
    "ok" "circle-check"
    "watch" "alert-circle"
    "alert" "alert-triangle"
    "info" "info-circle"
    ;; neutral: simple, widely available tabler icon
    "box"))

(defn chip-parts
  "Split 'Tareas abiertas 12' into optional label + trailing value if the last
  token looks numeric."
  [chip]
  (let [chip (str (or chip ""))
        tokens (vec (remove string/blank? (string/split (string/trim chip) #"\s+")))]
    (if (and (>= (count tokens) 2)
             (re-matches #"[+-]?[\d]+([.,]\d+)?%?" (str (last tokens))))
      {:label (string/join " " (butlast tokens))
       :value (str (last tokens))}
      {:label chip
       :value nil})))
