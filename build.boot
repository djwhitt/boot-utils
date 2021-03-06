(set-env!
  :source-paths #{"src"}
  :dependencies
  '[[adzerk/bootlaces "0.1.13" :scope "test"]
    [boot/core "2.1.2" :scope "provided"]
    [cheshire "5.5.0"]
    [ns-tracker "0.3.0"]
    [org.clojure/clojure "1.6.0" :scope "provided"]
    [org.clojure/tools.namespace "0.2.11"]])

(require
  '[adzerk.bootlaces :refer :all]
  '[boot.pod         :as pod]
  '[boot.util        :as util]
  '[boot.core        :as core])

(def +version+ "0.4.1")

(bootlaces! +version+)

(task-options!
  pom {:project     'djwhitt/boot-utils
       :version     +version+
       :description "A set of common boot tasks for my projects."
       :url         "https://github.com/djwhitt/boot-utils"
       :scm         {:url "https://github.com/djwhitt/boot-utils"}
       :license     {"Eclipse Public License" "http://www.eclipse.org/legal/epl-v10.html"}})
