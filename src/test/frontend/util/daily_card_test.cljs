(ns frontend.util.daily-card-test
  (:require [cljs.test :refer [deftest testing is]]
            [frontend.util.daily-card :as card]))

(deftest test-parse-kv-form
  (let [c (card/parse-card-args
           ["title=Estado" "tone=ok" "metric=12" "body=Tareas abiertas 12 · cerradas 5"])]
    (is (= "Estado" (:title c)))
    (is (= "ok" (:tone c)))
    (is (= "12" (:metric c)))
    (is (= ["Tareas abiertas 12" "cerradas 5"] (:chips c)))))

(deftest test-parse-posicional
  (let [c (card/parse-card-args ["Estado" "ok" "Atajo posicional · con chips"])]
    (is (= "Estado" (:title c)))
    (is (= "ok" (:tone c)))
    (is (= ["Atajo posicional" "con chips"] (:chips c)))))

(deftest test-tone-y-layout-desconocidos-caen-al-defecto
  (let [c (card/parse-card-args ["title=X" "tone=morado" "layout=gigante" "body=y"])]
    (is (= "neutral" (:tone c)))
    (is (= "default" (:layout c)))))

(deftest test-chips-explicitos-ganan-al-body
  (let [c (card/parse-card-args ["title=X" "chips=a|b|c" "body=uno · dos"])]
    (is (= ["a" "b" "c"] (:chips c)))))

(deftest test-body-de-una-sola-parte-no-es-chips
  (let [c (card/parse-card-args ["title=X" "body=Solo una frase"])]
    (is (nil? (:chips c)))
    (is (= "Solo una frase" (:body c)))))

;; Regression: every one of these threw "Index out of bounds" before, because a
;; title coming from `title=` still made the parser drop a free argument that
;; wasn't there. `{{card title=…, chips=…}}` is an example in the guide itself.
(deftest test-tarjetas-sin-body-no-explotan
  (testing "solo kv, sin body"
    (let [c (card/parse-card-args ["title=Estado" "tone=ok"])]
      (is (= "Estado" (:title c)))
      (is (= "ok" (:tone c)))
      (is (nil? (:body c)))))
  (testing "solo título"
    (is (= "Estado" (:title (card/parse-card-args ["title=Estado"])))))
  (testing "chips explícitos sin body — el ejemplo de la guía"
    (let [c (card/parse-card-args ["title=En curso" "tone=neutral" "metric=3"
                                   "chips=Informe|Diseño|Contratos"])]
      (is (= "En curso" (:title c)))
      (is (= ["Informe" "Diseño" "Contratos"] (:chips c)))
      (is (nil? (:body c))))))

(deftest test-titulo-kv-no-se-come-el-body-posicional
  (testing "with title= present, free arguments are all body"
    (let [c (card/parse-card-args ["title=X" "uno" "dos"])]
      (is (= "X" (:title c)))
      (is (= "uno, dos" (:body c))))))

(deftest test-sin-argumentos
  (let [c (card/parse-card-args [])]
    (is (nil? (:title c)))
    (is (= "neutral" (:tone c)))
    (is (nil? (:body c)))))

(deftest test-comillas-se-quitan
  (let [c (card/parse-card-args ["title=\"Con comillas\"" "body='y aquí'"])]
    (is (= "Con comillas" (:title c)))
    (is (= "y aquí" (:body c)))))

(deftest test-clave-con-dos-puntos
  (let [c (card/parse-card-args ["title: Estado" "tone: watch"])]
    (is (= "Estado" (:title c)))
    (is (= "watch" (:tone c)))))

(deftest test-extra-recoge-claves-desconocidas
  (is (= {"color" "rojo"} (:extra (card/parse-card-args ["title=X" "color=rojo"])))))

(deftest test-chip-parts
  (is (= {:label "Tareas abiertas" :value "12"} (card/chip-parts "Tareas abiertas 12")))
  (is (= {:label "Sin número" :value nil} (card/chip-parts "Sin número")))
  (is (= {:label "Caída" :value "-3,5%"} (card/chip-parts "Caída -3,5%"))))

(deftest test-split-chips
  (is (= ["a" "b"] (card/split-chips "a · b")))
  (is (= ["a" "b"] (card/split-chips "a|b")))
  (is (nil? (card/split-chips "solo uno")))
  (is (nil? (card/split-chips nil))))

(deftest test-tone-icon
  (is (= "circle-check" (card/tone-icon "ok")))
  (is (= "box" (card/tone-icon "neutral")))
  (is (= "box" (card/tone-icon "desconocido"))))
