(ns frontend.components.daily-card
  "Generic visual card macro for daily notes (and any page).

  Syntax (comma-separated macro arguments):
    {{card title=Estado, tone=ok, layout=default, metric=12, body=Abiertas 12}}

  Also accepted:
    {{card Estado, ok, Abiertas 12 · Cerradas 5}}
      → title, tone (if known), rest as body

  Body with middle-dot (·) or pipe (|) separators becomes visual chips:
    body=Abiertas 12 · Cerradas 5 · Bloqueadas 0
  Or explicit:
    chips=Abiertas 12|Cerradas 5|Bloqueadas 0

  Tones: ok | watch | alert | info | neutral
  Layouts: compact | default | wide

  Agent-agnostic: anyone can write these blocks (human, script, or AI)."
  (:require [clojure.string :as string]
            [frontend.components.macro :as component-macro]
            [frontend.ui :as ui]
            [frontend.util.daily-card :as card-util]
            [lambdaisland.glogi :as log]
            [rum.core :as rum]))

(rum/defc chips-row
  [chips]
  (into
   [:div.daily-card__chips]
   (map-indexed
    (fn [idx chip]
      (let [{:keys [label value]} (card-util/chip-parts chip)]
        [:span.daily-card__chip {:key (str "chip-" idx)}
         [:span.daily-card__chip-label (or label "")]
         (when-not (string/blank? (str value))
           [:span.daily-card__chip-value (str value)])]))
    (or chips []))))

(rum/defc card-view
  [{:keys [title subtitle tone layout icon metric body chips]}]
  (let [tone (or tone "neutral")
        layout (or layout "default")
        icon-name (if-not (string/blank? (str icon)) (str icon) (card-util/tone-icon tone))
        use-chips? (boolean (seq chips))]
    [:div.daily-card
     {:class (str "daily-card--tone-" tone
                  " daily-card--layout-" layout
                  (when use-chips? " daily-card--has-chips"))
      :data-tone tone
      :data-layout layout}
     [:div.daily-card__accent]
     [:div.daily-card__inner
      [:div.daily-card__header
       [:div.daily-card__title-row
        [:span.daily-card__icon
         (try
           (ui/icon icon-name {:size (if (= layout "compact") 14 16)})
           (catch :default _
             (ui/icon "box" {:size (if (= layout "compact") 14 16)})))]
        (when-not (string/blank? (str title))
          [:span.daily-card__title (str title)])
        (when-not (string/blank? (str metric))
          [:span.daily-card__metric (str metric)])]
       (when (and (not= layout "compact")
                  (not (string/blank? (str subtitle))))
         [:div.daily-card__subtitle (str subtitle)])]
      (cond
        use-chips?
        (chips-row chips)

        (and body (not (and (string? body) (string/blank? body))))
        [:div.daily-card__body body]

        :else nil)]]))

(rum/defc card-macro*
  [config options]
  (try
    (let [opts (card-util/parse-card-args (:arguments options))
          format (get-in config [:block :block/format] :markdown)
          inline-text (:inline-text config)
          body (:body opts)
          chips (:chips opts)
          body-node (when (and (not (seq chips))
                               body
                               (not (string/blank? body)))
                      (if (fn? inline-text)
                        (try
                          (inline-text config format body)
                          (catch :default _ body))
                        body))]
      (card-view (assoc opts :body body-node)))
    (catch :default e
      (log/error :daily-card/render-failed e)
      [:div.daily-card.daily-card--tone-alert
       [:div.daily-card__accent]
       [:div.daily-card__inner
        [:div.daily-card__title "Card error"]
        [:div.daily-card__body
         (str "No se pudo renderizar la card. Revisa la sintaxis. "
              (or (.-message e) e))]]])))

(defn- card-macro-entry
  [config options]
  (card-macro* config options))

;; Side-effect registration (this ns must be required at app startup)
(component-macro/register "card" card-macro-entry)
