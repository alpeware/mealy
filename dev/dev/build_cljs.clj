(ns dev.build-cljs
  "ClojureScript build script using cljs.build.api.
   Compiles the dev.frontend namespace into a single JS file
   for the Mealy dev dashboard SPA."
  (:require [cljs.build.api :as cljs]))

(defn -main
  "Compiles ClojureScript sources from the dev directory."
  [& _args]
  (println "Compiling ClojureScript...")
  (cljs/build "dev"
              {:main 'dev.frontend
               :output-to "dev/resources/public/js/main.js"
               :output-dir "dev/resources/public/js/out"
               :asset-path "/js/out"
               :optimizations :whitespace
               :pretty-print true})
  (println "ClojureScript compilation complete → dev/resources/public/js/main.js"))
