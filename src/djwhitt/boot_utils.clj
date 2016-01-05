(ns djwhitt.boot-utils
  {:boot/export-tasks true}
  (:require
    [boot.core :as core]
    [boot.util :as util]
    [cheshire.core :as cheshire]
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
  [d dev-ns     DEV-NS sym   "Dev namespace (must call reloaded.repl/set-init!)."
   a auto-start        bool  "Auto-starts the system."
   r hot-reload        bool  "Enables hot-reloading."
   t test-ns-regex     regex "Regex matching namespaces with tests to run after refresh."]
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
  [m main-namespace NAMESPACE str   "The namespace containing a -main function to invoke."
   a arguments      EXPR      [edn] "An optional argument sequence to apply to the -main function."]
  (core/with-pre-wrap fs
    (require (symbol main-namespace) :reload)
    (if-let [f (resolve (symbol main-namespace "-main"))]
      (apply f arguments)
      (throw (ex-info "No -main method found" {:main-namespace main-namespace})))
    fs))

;; Derived from: https://github.com/jeluard/boot-notify/blob/master/src/jeluard/boot_notify.clj
(core/deftask notify
  "Visible notifications during build."
  [m template FOO=BAR  {kw str}  "Templates overriding default messages. Keys can be :success, :warning or :failure."
   t title             str       "Title of the notification."
   n notifier          code      "Notification function."]
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
