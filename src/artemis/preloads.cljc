(ns artemis.preloads
  (:require [devtools.core :as devtools]))

#?(:cljs
   (devtools/install!)
   (enable-console-print!))
