;; # Router

;; ## Namespace
(ns re-frame.router
  (:refer-clojure :exclude [flush])
  (:require-macros [cljs.core.async.macros :refer [go-loop go]])
  (:require [reagent.core        :refer [flush]]
            [re-frame.handlers   :refer [handle]]
            [re-frame.utils      :refer [warn error]]
            [cljs.core.async     :refer [chan put! <! timeout]]))

;; ## The Event Conveyor Belt


;; Moves events from "dispatch" to the router loop.
;; Using core.async means we can have the aysnc handling of events.
;;
;; TODO: set buffer size?
(def ^:private event-chan (chan))    

(defn purge-chan
  "read all pending events from the channel and drop them on the floor"
  []
  #_(loop []                          
    ;; TODO commented out until poll! is a part of the core.asyc API. Progress on [GitHub](https://github.com/clojure/core.async/commit/d8047c0b0ec13788c1092f579f03733ee635c493)
    (when (go (poll! event-chan))    
      (recur))))

;; ## Router loop

;; In a perpetual loop, read events from `event-chan`, and call the right handler.
;;
;; Because handlers occupy the CPU, before each event is handled, hand
;; back control to the browser, via a `(<! (timeout 0))` call.
;;
;; In some cases, we need to pause for an entire animationFrame, to ensure that
;; the DOM is fully flushed, before then calling a handler known to hog the CPU
;; for an extended period.  In such a case, the event should be laballed with metadata
;; Example usage (notice the `:flush-dom` metadata):
;; ```clojure
;; (dispatch ^:flush-dom  [:event-id other params])
;; ```

(defn router-loop
  []
  (go-loop []
           ;; Wait for an event
           (let [event-v  (<! event-chan)                   
                ;; Check the event for metadata
                 _        (if (:flush-dom (meta event-v))
                            ;; Wait just over one annimation frame (16ms),
                            ;; to rensure all pending GUI work is flushed to the DOM.
                            (do (flush) (<! (timeout 20)))  
                            ;; Just in case we are handling one dispatch after another,
                            ;; give the browser back control to do its stuff
                            (<! (timeout 0)))]              
             (try
               (handle event-v)

               ;; If the handler throws:

               ;;   - allow the exception to bubble up because the app, in production,
               ;;     may have hooked window.onerror and perform special processing.
               ;;   - But an exception which bubbles up will break the enclosing go-loop.
               ;;     So we'll need to start another one.
               ;;   - purge any pending events, because they are probably related to the
               ;;     event which just fell in a screaming heap. Not sane to handle further
               ;;     events if the prior event failed.
               (catch js/Object e
                 ;; Try to recover from this (probably uncaught) error as best we can
                 (do
                   ;; Get rid of any pending events
                   (purge-chan)        
                   ;; Exception throw will cause termination of go-loop. So, start another.
                   (router-loop) 
                   ;; Re-throw so the rest of the app's infrastructure (window.onerror?) gets told
                   (throw e)))))
           (recur)))

;; start event processing
(router-loop)


;; ## Dispatch

(defn dispatch
  "Send an event to be processed by the registered handler.

  Usage example:
  ```clojure
  (dispatch [:delete-item 42])
  ```
  "
  [event-v]
  (if (nil? event-v)
    ;; `nil` would close the channel
    (error "re-frame: \"dispatch\" is ignoring a nil event.")     
    (put! event-chan event-v))
  ;; Ensure `nil` return.
  ;; See [the dangers of returning false](https://github.com/Day8/re-frame/wiki/Beware-Returning-False)
  nil)   


(defn dispatch-sync
  "Send an event to be processed by the registered handler, but avoid the async-inducing
  use of **core.async/chan**.

  Usage example:
  ```clojure
  (dispatch-sync [:delete-item 42])
  ```
  "
  [event-v]
  (handle event-v)
  ;; Ensure nil return.
  ;; See [the dangers of returning false](https://github.com/Day8/re-frame/wiki/Beware-Returning-False)
  nil)    
