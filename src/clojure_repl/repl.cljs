(ns clojure-repl.repl
  (:require [cljs.nodejs :as node]
            [clojure.string :as string]
            [oops.core :refer [oget ocall]]
            [clojure-repl.common :as common :refer [repls
                                                    console-log]]))


;; TODO: Support having multiple REPLs
;; TODO: Support sending multiple messages to repl

(defn append-to-output-editor
  "Appends text at the end of the output editor."
  [project-name output & {:keys [add-newline?] :or {add-newline? true}}]
  (when-let [output-editor (get-in @repls [project-name :host-output-editor])]
    (common/append-to-editor output-editor output :add-newline? add-newline?)
    (console-log "OUTPUT: " project-name output)))

(defmulti stop-process
  "Kills the local repl process, if any, and disconnects from the repl server.
  Until we support multiple simultaneous repls, starting a repl will call this
  first."
  (fn [project-name]
    (get-in @repls [project-name :repl-type])))

(defmethod stop-process :default [_]
  (fn []))

(defn interrupt-process [])

(defmulti execute-code
  "Appends the code to editor and sends it over to repl."
  (fn [project-name code & [options]]
    (get-in @repls [project-name :repl-type])))
