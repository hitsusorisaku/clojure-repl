(ns clojure-repl.host
  (:require [clojure.string :as string :refer [ends-with? trim trim-newline replace]]
            [clojure-repl.repl :as repl]
            [clojure-repl.execution :as execution]
            [clojure-repl.strings :refer [output-editor-title
                                          input-editor-title
                                          execute-comment
                                          output-editor-placeholder]]
            [clojure-repl.common :as common :refer [add-subscription
                                                    destroy-editor
                                                    dispose-project-if-empty
                                                    repls]]))

;; TODO: Combine the output editor and input editor into a single paneItem.

(defn set-grammar
  "Sets the grammer of the editor as Clojure."
  [editor]
  (.setGrammar editor (.grammarForScopeName (.-grammars js/atom) "source.clojure")))

(defn destroy-editors
  "Destroys both output and input editors."
  [project-name]
  (destroy-editor project-name :host-output-editor)
  (destroy-editor project-name :host-input-editor))

(defn dispose [project-name]
  (destroy-editors project-name)
  (.dispose (get-in @repls [project-name :subscriptions]))
  (swap! repls update project-name #(assoc % :subscriptions nil)))

;; TODO: Ignore any key commands inside the output-editor
(defn create-output-editor
  "Opens a text editor for displaying repl outputs."
  [project-name]
  (-> (.-workspace js/atom)
      (.open (str output-editor-title " " project-name) (js-obj "split" "right"))
      (.then (fn [editor]
                (set! (.-isModified editor) (fn [] false))
                (set! (.-isModified (.getBuffer editor)) (fn [] false))
                (.setSoftWrapped editor true)
                (.add (.-classList (.-element editor)) "repl-history")
                (set-grammar editor)
                (.moveToBottom editor)
                (swap! repls update project-name #(assoc % :host-output-editor editor))
                (.setPlaceholderText editor output-editor-placeholder)
                (add-subscription project-name
                                  (.onDidDestroy editor (fn [event]
                                                          (swap! repls update project-name #(assoc % :host-output-editor nil))
                                                          (repl/stop-process project-name)
                                                          (dispose project-name)
                                                          (dispose-project-if-empty project-name))))))))

(defn find-non-blank-last-row [buffer]
  (let [last-row (.getLastRow buffer)]
    (if (.isRowBlank buffer last-row)
      (.previousNonBlankRow buffer last-row)
      last-row)))

;; TODO: Set a placeholder text to notify user when repl is ready.
(defn create-input-editor
  "Opens a text editor for simulating repl's entry area. Adds a listener
  onDidStopChanging to look for execute-comment entered by guest side using
  teletype in the entry, so that it can detect when to execute the code."
  [project-name]
  (-> (.-workspace js/atom)
      (.open (str input-editor-title " " project-name) (js-obj "split" "down"))
      (.then (fn [editor]
                (set! (.-isModified editor) (fn [] false))
                (set! (.-isModified (.getBuffer editor)) (fn [] false))
                (.setSoftWrapped editor true)
                (.add (.-classList (.-element editor)) "repl-entry")
                (set-grammar editor)
                (swap! repls update project-name #(assoc % :host-input-editor editor))
                (add-subscription project-name
                                  (.onDidStopChanging editor (fn [event]
                                                               (let [buffer (.getBuffer editor)
                                                                     non-blank-row (find-non-blank-last-row buffer)
                                                                     last-text (.lineForRow buffer non-blank-row)]
                                                                  (when (ends-with? (trim last-text) execute-comment)
                                                                    (.deleteRows buffer (inc non-blank-row) (inc (.getLastRow buffer)))
                                                                    (execution/execute-entered-text project-name editor))))))
                (add-subscription project-name
                                  (.onDidDestroy editor (fn [event]
                                                          (swap! repls update project-name #(assoc % :host-input-editor nil))
                                                          (repl/stop-process project-name)
                                                          (dispose project-name)
                                                          (dispose-project-if-empty project-name))))))))

;; TODO: Make sure to create input editor after output editor has been created.
(defn create-editors [project-name]
  (create-output-editor project-name)
  (create-input-editor project-name))
