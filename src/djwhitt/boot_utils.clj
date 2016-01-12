(ns djwhitt.boot-utils
  {:boot/export-tasks true}
  (:require
    [boot.core :as core]
    [boot.util :as util]
    [cheshire.core :as cheshire]
    [clojure.java.io :as io]
    [clojure.java.shell :as sh]
    [clojure.stacktrace :refer [print-stack-trace]]
    [clojure.test]
    [ns-tracker.core :refer :all]
    [reloaded.repl :refer [set-init! reset go]])
  (:import
    [java.io File FileOutputStream]))

(defn- send-notify-notify-impl-fn []
  (fn [n]
    (let [{:keys [title message urgency]} n]
      (sh/sh "notify-send" "-t" "2000" "-u" urgency title message))))

(defn- fifo-notify-impl-fn [path]
  (fn [n]
    (when (.exists (File. path))
      (with-open [out (FileOutputStream. path)]
        (.write out (.getBytes (str (cheshire/generate-string n) "\n")))))))

(defn- notifier-fn [notifier-impl]
  (fn notify!
    ([n]
     (let [n (merge {:urgency "normal"} n)]
       (notifier-impl n)))
    ([n m]
     (notify! (assoc n :message m)))
    ([n m u]
     (notify! (assoc n :urgency u) m))))

(defn make-send-notify-notifier []
  (notifier-fn (send-notify-notify-impl-fn)))

(defn make-fifo-notifier [path]
  (notifier-fn (fifo-notify-impl-fn path)))

;; Derived from: https://github.com/danielsz/system/blob/master/src/system/boot.clj
(core/deftask dev-system
  "Load dev namespace, start system, and reset when files change."
  [d dev-ns        NS    sym   "Dev namespace (must call reloaded.repl/set-init!)."
   a auto-start          bool  "Auto-starts the system."
   r hot-reload          bool  "Enables hot-reloading."
   t test-ns-regex REGEX regex "Regex matching namespaces with tests to run after refresh."]
  (let [fs-prev-state (atom nil)
        dirs (core/get-env :directories)
        modified-namespaces (ns-tracker (into [] dirs))
        auto-start (delay
                     (when auto-start
                       (require dev-ns)
                       (util/info (str "Autostarting the system: " (go) "\n"))))
        set-refresh-dirs (delay
                           (apply clojure.tools.namespace.repl/set-refresh-dirs dirs))]
    (core/with-pre-wrap fileset
      @set-refresh-dirs
      @auto-start
      (when-let [modified (modified-namespaces)]
        (when hot-reload
          (with-bindings {#'*ns* *ns* ; because of exception "Can't set!: *ns* from non-binding thread"
                          #'*e   nil}
            (let [result (reset)]
              (when *e (throw *e))))))
      (when test-ns-regex
        (util/info (str "Running tests matching: " test-ns-regex "\n"))
        (let [{:as test-results :keys [fail error]} (clojure.test/run-all-tests test-ns-regex)]
          (when (or (> fail 0) (> error 0))
            (throw (ex-info "Test failure or error" test-results)))))
      (reset! fs-prev-state fileset))))

(core/deftask run
  "Run the -main function in some namespace with arguments."
  [m main-namespace NS   str   "The namespace containing a -main function to invoke."
   a arguments      ARGS [edn] "An optional argument sequence to apply to the -main function."]
  (core/with-pre-wrap fs
    (require (symbol main-namespace) :reload)
    (if-let [f (resolve (symbol main-namespace "-main"))]
      (apply f arguments)
      (throw (ex-info "No -main method found" {:main-namespace main-namespace})))
    fs))

;; Derived from: https://github.com/jeluard/boot-notify/blob/master/src/jeluard/boot_notify.clj
(core/deftask notify
  "Visible notifications during build."
  [m template TYPE=MSG {kw str} "Templates overriding default messages. Keys can be :success, :warning or :failure."
   t title    TITLE    str      "Title of the notification."
   n notifier FN       code     "Notification function."]
  (let [title (or title "Boot notify")
        base-notification {:title title :urgency "normal"}
        messages (merge {:success "Success!" :warning "%s warning/s" :failure "%s"} template)]
    (fn [next-task]
      (fn [fileset]
        (try
          (util/with-let [_ (next-task fileset)]
            (if (zero? @core/*warnings*)
              (notifier base-notification (:success messages))
              (notifier base-notification (format (:warning messages) @core/*warnings*) "critical")))
          (catch Throwable t
            (notifier base-notification (format (:failure messages) (.getMessage t)) "critical")
            (throw t)))))))

;; Devcards code is based on https://github.com/adzerk-oss/boot-reload/blob/master/src/adzerk/boot_reload.clj
(defn- add-devcards!
  [in-file out-file ns init-fn]
  (let [spec (-> in-file slurp read-string)
        compiler-options {:devcards true}]
    (util/info "Adding :compiler-options %s, :init-fn %s, and :require %s to %s...\n"
               (pr-str compiler-options) init-fn ns (.getName in-file))
    (io/make-parents out-file)
    (-> spec
        (update-in [:require] conj ns)
        (update-in [:init-fns] conj init-fn)
        (update-in [:compiler-options] conj compiler-options)
        pr-str
        ((partial spit out-file)))))

(defn- relevant-cljs-edn [fileset ids]
  (let [relevant  (map #(str % ".cljs.edn") ids)
        f         (if ids
                    #(core/by-path relevant %)
                    #(core/by-ext [".cljs.edn"] %))]
    (-> fileset core/input-files f)))

(core/deftask devcards
  "Add devcards require to cljs.edn files."
  [b ids         BUILD_IDS #{str} "Only inject devcards into these builds (= .cljs.edn files)"
   n devcards-ns NS        sym    "Namespace containing devcards init function."
   i init-fn     FN        sym    "Devcards init function."]
  (let [tmp (core/tmp-dir!)
        prev-pre (atom nil)]
    (fn [next-task]
      (fn [fileset]
        (doseq [f (relevant-cljs-edn (core/fileset-diff @prev-pre fileset) ids)]
          (let [path     (core/tmp-path f)
                in-file  (core/tmp-file f)
                out-file (io/file tmp path)]
            (add-devcards! in-file out-file devcards-ns init-fn)))
        (reset! prev-pre fileset)
        (-> fileset
            (core/add-resource tmp)
            core/commit!
            next-task)))))
