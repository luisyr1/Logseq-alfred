(ns frontend.util.cronograma-test
  (:require [cljs.test :refer [deftest testing is]]
            [frontend.util.cronograma :as c]))

;; Stub resolver: only knows the two titles used in these tests.
(defn- resolver
  [titulo]
  (get {"Viernes 7 de Agosto, 2026" 20260807
        "Sábado 8 de Agosto, 2026" 20260808}
       titulo))

(defn- evento
  ([content] (evento content nil))
  ([content properties]
   (cond-> {:block/content content}
     properties (assoc :block/properties properties))))

(deftest test-primera-linea
  (is (= "**07:35-07:55** Llevar el coche al taller"
         (c/primera-linea "**07:35-07:55** Llevar el coche al taller\ncalendario:: Personal\nid:: 123")))
  (is (nil? (c/primera-linea "")))
  (is (nil? (c/primera-linea nil)))
  (testing "a block that is only properties has no title line"
    (is (nil? (c/primera-linea "calendario:: Personal")))))

(deftest test-parse-evento-formato-nuevo
  (let [e (c/parse-evento (evento "**07:35-07:55** Llevar el coche al taller"
                                  {:calendario "Personal"}))]
    (is (= "Llevar el coche al taller" (:titulo e)))
    (is (= 455 (:inicio e)))
    (is (= 475 (:fin e)))
    (is (false? (:todo-el-dia? e)))
    (is (= [[:calendario "Personal"]] (:chips e)))))

(deftest test-parse-evento-todo-el-dia
  (let [e (c/parse-evento (evento "**todo el día** Festivo local"))]
    (is (= "Festivo local" (:titulo e)))
    (is (true? (:todo-el-dia? e)))
    (is (nil? (:inicio e)))))

(deftest test-parse-evento-hora-suelta
  (let [e (c/parse-evento (evento "**18:15** Clase de guitarra"))]
    (is (= "Clase de guitarra" (:titulo e)))
    (is (= 1095 (:inicio e)))
    (is (nil? (:fin e)))
    (is (false? (:todo-el-dia? e)))))

(deftest test-parse-evento-formato-legacy
  (testing "the flat format Alfred writes today still parses"
    (let [e (c/parse-evento (evento "**Viernes 7, 08:00-09:30** — Deporte. #Personal"))]
      (is (= "Deporte." (:titulo e)))
      (is (= 480 (:inicio e)))
      (is (= 570 (:fin e)))
      (is (= ["Personal"] (:tags e)))))
  (testing "legacy all-day"
    (let [e (c/parse-evento (evento "**Viernes 7, todo el día** — Festivo local. #Trabajo"))]
      (is (= "Festivo local." (:titulo e)))
      (is (true? (:todo-el-dia? e)))
      (is (= ["Trabajo"] (:tags e))))))

(deftest test-parse-evento-sin-hora
  (testing "an unparseable line still renders as a plain row"
    (let [e (c/parse-evento (evento "Repasar el correo antes de comer"))]
      (is (= "Repasar el correo antes de comer" (:titulo e)))
      (is (nil? (:inicio e)))
      (is (true? (:todo-el-dia? e))))))

(deftest test-parse-evento-estado
  (is (= :hecho (:estado (c/parse-evento (evento "**08:00** Deporte" {:estado "hecho"})))))
  (is (= :cancelado (:estado (c/parse-evento (evento "**08:00** Deporte" {:estado "Cancelado"})))))
  (is (= :movido (:estado (c/parse-evento (evento "**08:00** Deporte" {:estado "movido a mañana"})))))
  (is (nil? (:estado (c/parse-evento (evento "**08:00** Deporte")))))
  (is (nil? (:estado (c/parse-evento (evento "**08:00** Deporte" {:estado ""}))))))

(deftest test-parse-evento-chips-ocultan-ruido
  (let [e (c/parse-evento (evento "**08:00** Deporte"
                                  {:id "abc" :collapsed true :estado "hecho"
                                   :calendario "Personal" :ubicacion "Gimnasio"}))]
    (is (= [[:calendario "Personal"] [:ubicacion "Gimnasio"]] (:chips e)))))

(deftest test-parse-dia
  (let [d (c/parse-dia {:block/content "[[Viernes 7 de Agosto, 2026]]"
                        :block/children [(evento "**08:00-09:30** Deporte")
                                         (evento "**todo el día** Festivo local")
                                         (evento "**07:35-07:55** Llevar el coche al taller")]}
                       resolver)]
    (is (= 20260807 (:journal-day d)))
    (is (= "Viernes 7" (:etiqueta d)))
    (is (= 1 (count (:todo-el-dia d))))
    (testing "timed events come back sorted by start time"
      (is (= ["Llevar el coche al taller" "Deporte"] (mapv :titulo (:eventos d)))))))

(deftest test-parse-dia-iso
  (let [d (c/parse-dia {:block/content "2026-08-08"
                        :block/children [(evento "**18:15-20:00** Guitarra")]}
                       (constantly nil))]
    (is (= 20260808 (:journal-day d)))))

(deftest test-parse-dia-vacio
  (let [d (c/parse-dia {:block/content "[[Sábado 8 de Agosto, 2026]]"
                        :block/children []}
                       resolver)]
    (is (= 20260808 (:journal-day d)))
    (is (empty? (:eventos d)))))

(deftest test-parse-cronograma-por-dias
  (let [dias (c/parse-cronograma
              [{:block/content "[[Sábado 8 de Agosto, 2026]]"
                :block/children [(evento "**18:15-20:00** Guitarra")]}
               {:block/content "[[Viernes 7 de Agosto, 2026]]"
                :block/children [(evento "**08:00-09:30** Deporte")]}]
              {:ancla "Viernes 7 de Agosto, 2026" :resolver resolver})]
    (testing "days come back in chronological order regardless of block order"
      (is (= [20260807 20260808] (mapv :journal-day dias))))))

(deftest test-parse-cronograma-plano
  (testing "a flat list of events (no day blocks) lands in the anchor day"
    (let [dias (c/parse-cronograma
                [(evento "**Viernes 7, 08:00-09:30** — Deporte. #Personal")
                 (evento "**Viernes 7, 07:35-07:55** — Llevar el coche al taller. #Personal")]
                {:ancla "Viernes 7 de Agosto, 2026" :resolver resolver})]
      (is (= 1 (count dias)))
      (is (= 20260807 (:journal-day (first dias))))
      (is (= ["Llevar el coche al taller." "Deporte."] (mapv :titulo (:eventos (first dias))))))))

;; ---------------------------------------------------------------------------
;; Layout

(deftest test-layout-hueco-corto-es-proporcional
  (let [segs (c/layout [{:inicio 480 :fin 500} {:inicio 520 :fin 560}])
        hueco (first (filter #(= :hueco (:tipo %)) segs))]
    (is (= 3 (count segs)))
    (is (= 20 (:minutos hueco)))
    (is (false? (:comprimido? hueco)))
    (is (< (:alto hueco) 46) "a 20 min gap must be visibly shorter than an event")))

(deftest test-layout-hueco-largo-se-comprime
  (let [segs (c/layout [{:inicio 480 :fin 570} {:inicio 1095 :fin 1200}])
        hueco (first (filter #(= :hueco (:tipo %)) segs))]
    (is (true? (:comprimido? hueco)))
    (is (= 525 (:minutos hueco)))
    (is (= 26 (:alto hueco))
        "an 8h45 gap collapses to the same fixed height as any other long gap")))

(deftest test-layout-sin-huecos-negativos
  (testing "overlapping events produce no gap segment"
    (let [segs (c/layout [{:inicio 480 :fin 600} {:inicio 540 :fin 660}])]
      (is (= 2 (count segs)))
      (is (every? #(= :evento (:tipo %)) segs)))))

(deftest test-alto-evento-acotado
  (is (= 46 (c/alto-evento {:inicio 480 :fin 490})) "short events keep a readable minimum")
  (is (= 130 (c/alto-evento {:inicio 0 :fin 600})) "long events are capped")
  (is (= 46 (c/alto-evento {:inicio nil :fin nil}))))

(deftest test-en-curso-y-pasado
  (let [gym {:inicio 480 :fin 570}]
    (is (true? (c/en-curso? gym 500)))
    (is (false? (c/en-curso? gym 600)))
    (is (true? (c/pasado? gym 600)))
    (is (false? (c/pasado? gym 500)))
    (is (false? (c/pasado? gym 400))))
  (testing "a manual mark wins over the clock"
    (is (true? (c/pasado? {:inicio 1095 :fin 1200 :estado :hecho} 600))))
  (testing "no clock (another day) means nothing is past by itself"
    (is (false? (c/pasado? {:inicio 480 :fin 570} nil)))))

(deftest test-inserta-ahora
  (let [segs (c/layout [{:inicio 480 :fin 570} {:inicio 1095 :fin 1200}])]
    (testing "marker goes before the next upcoming event"
      (let [con (c/inserta-ahora segs 700)
            idx (.indexOf (to-array (mapv :tipo con)) "ahora")]
        (is (some #(= :ahora (:tipo %)) con))
        (is (< idx (dec (count con))))))
    (testing "no marker while an event is in progress — that one is highlighted"
      (is (not-any? #(= :ahora (:tipo %)) (c/inserta-ahora segs 500))))
    (testing "after everything, the marker lands at the end"
      (is (= :ahora (:tipo (last (c/inserta-ahora segs 1300))))))
    (testing "before everything, the marker leads"
      (is (= :ahora (:tipo (first (c/inserta-ahora segs 400))))))
    (testing "another day inserts nothing"
      (is (= segs (c/inserta-ahora segs nil))))))

;; ---------------------------------------------------------------------------
;; Formatting

(deftest test-minutos->hhmm
  (is (= "07:35" (c/minutos->hhmm 455)))
  (is (= "00:00" (c/minutos->hhmm 0)))
  (is (= "23:59" (c/minutos->hhmm 1439)))
  (is (nil? (c/minutos->hhmm nil))))

(deftest test-formatea-duracion
  (is (= "45 min" (c/formatea-duracion 45)))
  (is (= "1 h 30" (c/formatea-duracion 90)))
  (is (= "8 h" (c/formatea-duracion 480)))
  (is (nil? (c/formatea-duracion 0)))
  (is (nil? (c/formatea-duracion nil))))

(deftest test-nombres-de-dia
  (is (= "Viernes 7" (c/nombre-dia 20260807)))
  (is (= "Sáb 8" (c/nombre-dia-corto 20260808)))
  (is (= "Agosto" (c/nombre-mes 20260807)))
  (is (nil? (c/nombre-dia nil))))

(deftest test-journal-day-round-trip
  (is (= 20260807 (c/date->journal-day (c/journal-day->date 20260807))))
  (is (nil? (c/journal-day->date nil))))
