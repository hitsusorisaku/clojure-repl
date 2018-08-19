(ns clojure-repl.common
  "The garage for storing code we need but don't know where to keep it."
  (:require [cljs.nodejs :as node]
            [cljs.pprint :refer [pprint]]
            [clojure.string :as string]))

(def node-atom (node/require "atom"))
(def fs (node/require "fs"))
(def CompositeDisposable (.-CompositeDisposable node-atom))

(def output-editor-title "Clojure REPL History")
(def input-editor-title "Clojure REPL Entry")
(def execute-comment ";execute")

(def max-history-count 100)

;; A map with project-name to TextEditor
(def repls (atom {}))

;; Template for repl's state
(def repl-state
  {:current-working-directory ""
   :process-env nil
   :lein-process nil
   :connection nil
   :session nil
   :host "localhost"
   :port nil
   :current-ns "user"
   :init-code nil
   :type nil
   :subscriptions nil
   :process nil
   :host-input-editor nil
   :host-output-editor nil
   :guest-input-editor nil
   :guest-output-editor nil
   :repl-history (list)
   :current-history-index -1})

(def state
  (atom {:disposables []
         :lein-path "/usr/local/bin" ;; TODO: Read this from Settings
         :most-recent-repl-project-name nil}))

; TODO: Can we just use println and the like instead?
(defn console-log
  "Used for development. The output can be viewed in the Atom's Console when in
  Dev Mode."
  [& output]
  (apply (.-log js/console) output))

(defn show-error [& error]
  (apply (.-error js/console) error)
  (.addError (.-notifications js/atom) (apply str error)))

(defn add-subscription
  "This should be wrapped whenever adding any subscriptions in order to dispose
  them later."
  [project-name disposable]
  (.add (get-in @repls [project-name :subscriptions]) disposable))

(defn add-repl-history [project-name code]
  (when (= max-history-count (count (get-in @repls [project-name :repl-history])))
    (swap! repls update project-name #(update % :repl-history butlast)))
  (swap! repls update project-name #(update % :repl-history (fn [history] (conj history code))))
  (swap! repls update project-name #(assoc % :current-history-index -1)))

;; TODO: Warn user when project.clj doesn't exist in the project.
(defn get-project-clj [project-path]
  (let [project-clj-path (str project-path "/project.clj")]
    (console-log "Looking for project.clj at " project-clj-path " - " (.existsSync fs project-clj-path))
    (.existsSync fs project-clj-path)))

(defn get-project-directory-from-path [root-project-path file-path]
  (loop [directories (butlast (string/split file-path #"/"))
         project-path root-project-path]
    (if (get-project-clj project-path)
      project-path
      (when (coll? directories)
        (recur (next directories) (str project-path "/" (first directories)))))))

;; TODO: Support having nested project folders. Right now it assumes that each
;;       project folder that's opened in Atom is an independent project. It
;;       should, however, allow user to open one big folder that contains
;;       multiple projects.
(defn get-project-path
  "Returns the project path of the given editor or nil if there is no project
  associated with the text-editor."
  [text-editor]
  (let [path (.getPath (.getBuffer text-editor))
        [directory-path, relative-path] (.relativizePath (.-project js/atom) path)]
    (when directory-path
      (get-project-directory-from-path directory-path relative-path))))

(defn get-active-project-path
  "Returns the path of the project that corresponds to the active editor or nil
  if no project is associated with the active text editor."
  []
  (get-project-path (.getActiveTextEditor (.-workspace js/atom))))

(defn get-project-name-from-path
  "Returns the project name from the given path or nil."
  [project-path]
  (when project-path
    (last (string/split project-path #"/"))))

(defn get-active-project-name
  []
  (get-project-name-from-path (get-active-project-path)))

(defn get-project-name-from-text-editor
  "Returns the project name from the path of the text editor."
  [editor]
  (when-let [project-path (get-project-path editor)]
    (get-project-name-from-path project-path)))

(defn get-project-name-from-input-editor
  "Returns the project name if there's a reference to the input editor."
  [editor]
  (some (fn [project-name]
          (console-log "Checking if repl exists for the project: " project-name)
          (when (or (= editor (get-in @repls [project-name :guest-input-editor]))
                    (= editor (get-in @repls [project-name :host-input-editor])))
            project-name))
        (keys @repls)))

(defn get-project-name-from-most-recent-repl
  "Returns a project name for the most recently used repl if it still exists."
  []
  (when-let [project-name (get @state :most-recent-repl-project-name)]
    (when (or (get-in @repls [project-name :host-input-editor])
              (get-in @repls [project-name :guest-input-editor]))
      project-name)))

(defn visible-repl? [text-editor]
  (when (and text-editor (.-element text-editor))
    (not= "none" (.-display (.-style (.-element text-editor))))))

(defn get-project-name-from-visible-repl []
  (some #(when (or (visible-repl? (get-in @repls [% :host-input-editor]))
                   (visible-repl? (get-in @repls [% :guest-input-editor])))
            %)
        (keys @repls)))

(defn get-all-project-names
  "Returns a list of all the current project names.

  NOTE: Atom can have multiple projects open at the same time with the same name
  if they are in different directories."
  []
  (->> (.getPaths (.-project js/atom))
       (map get-project-name-from-path)))

(defn add-repl [project-name & options]
  (swap! repls assoc project-name (-> (apply assoc repl-state options)
                                      (assoc :subscriptions (CompositeDisposable.)))))

;; TODO: Support destroying multiple editors with a shared buffer.
(defn ^:private close-editor
  "Searches through all the panes for the editor and destroys it."
  [editor]
  (doseq [pane (.getPanes (.-workspace js/atom))]
    (when (some #(= editor %) (.getItems pane))
      (.destroyItem pane editor))))

(defn destroy-editor
  "Destroys an editor defined in the state."
  [project-name editor-keyword]
  (when-let [editor (get-in @repls [project-name editor-keyword])]
    (close-editor editor)
    (swap! repls update project-name #(assoc % editor-keyword nil))))

(defn dispose-project-if-empty
  "Remove the project state if there's no running repls for the project name."
  [project-name]
  (when-not (or (get-in @repls [project-name :host-input-editor])
                (get-in @repls [project-name :guest-input-editor]))
    (swap! repls dissoc project-name)
    (when (= project-name (get @state :most-recent-repl-project-name))
      (swap! state assoc :most-recent-repl-project-name nil))))

;; TODO: Pretty print results
(defn append-to-editor
  "Appends text at the end of the editor. Always append a newline following the
  text unless specified not to. Because of the Clojure grammar set on the
  editor, we need to check if the last line only contains whitespaces, and move
  the cursor to the front of the line in order to ignore them. Otherwise, any
  text appended after that gets indented to the right."
  [editor text & {:keys [add-newline?] :or {add-newline? true}}]
  (when editor
    (.moveToBottom editor)
    (when (re-find #"^\s+$" (.getLastLine (.getBuffer editor)))
      (.moveToBeginningOfLine editor))
    (.insertText editor text)
    (when add-newline?
      (.insertNewlineBelow editor))
    (.scrollToBottom (.-element editor))
    (.moveToBottom editor)))
