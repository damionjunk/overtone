(ns
  ^{:doc "A simple event system that processes fired events in a thread pool."
     :author "Jeff Rose"}
  overtone.libs.event
  (:import (java.util.concurrent Executors LinkedBlockingQueue))
  (:use [overtone.util lib]
        [clojure.stacktrace]
        [clojure.set :only [intersection difference]])
  (:require [overtone.util.log :as log]))

(def NUM-THREADS (cpu-count))
(def ^{:dynamic true} *FORCE-SYNC?* false)
(defonce thread-pool (Executors/newFixedThreadPool NUM-THREADS))
(defonce event-handlers* (ref {}))
(defonce sync-event-handlers* (ref {}))

(defn- arg-count
  "Get the arity of a function."
  [f]
  (let [m (first (filter #(= "invoke" (.getName %)) (.getDeclaredMethods (class f))))
        p (.getParameterTypes m)]
    (alength p)))

(defn- on-event*
  [handler-ref* event-type key handler]
  (log/debug "adding-handler for " event-type)
  (dosync
   (let [handlers (get @handler-ref* event-type {})
         arity (if (var? handler) -1 (arg-count handler))]
     (alter handler-ref* assoc event-type (assoc handlers key [handler arity]))
     true)))

(defn- run-handler
  "Apply the handler to the args - handling exceptions gracefully."
  [handler & args]
  ;;deref vars so arg-count works correctly
  (let [handler (if (var? handler) @handler handler)]
    (try
      (apply handler (take (arg-count handler) args))
      (catch Exception e
        (log/debug "Handler Exception - got args:" args"\n"
                   (with-out-str (.printStackTrace e)))))))

(defn on-event
  "Asynchronously runs handler whenever events of event-type are fired. This
  asynchronous behaviour can be overridden if required - see sync-event for
  more information. Events may be triggered with the fns event and sync-event.

  Takes an event-type (name of the event), a handler fn and a key (to refer
  back to this handler in the future). The handler can optionally accept a
  single event argument, which is a map containing the :event-type property
  and any other properties specified when it was fired.

  (on-event \"/tr\" handler ::status-check )
  (on-event :midi-note-down (fn [event]
                              (funky-bass (:note event)))
                            ::midi-note-down-hdlr)

  Handlers can return :done to be removed from the handler list after execution."
  [event-type handler key]
  (on-event* event-handlers* event-type key handler))

(defn on-sync-event
  "Synchronously runs handler whenever events of type event-type are fired on
  the event handling thread i.e. causes the event handling thread to block until
  all sync events have been handled. Events may be triggered with the fns event
  and sync-event.

  Takes an event-type (name of the event), a handler fn and a key (to refer
  back to this handler in the future). The handler can optionally accept a
  single event argument, which is a map containing the :event-type property
  and any other properties specified when it was fired.

  (on-event \"/tr\" handler ::status-check )
  (on-event :midi-note-down (fn [event]
                              (funky-bass (:note event)))
                            ::midi-note-down-hdlr)

  Handlers can return :done to be removed from the handler list after execution."
  [event-type handler key]
  (on-event* sync-event-handlers* event-type key handler))

(defn remove-handler
  "Remove an event handler previously registered to handle events of event-type.
  Removes both sync and async handlers with a given key for a particular event
  type.

  (defn my-foo-handler [event] (do-stuff (:val event))

  (on-event :foo my-foo-handler ::bar-key)
  (event :foo :val 200) ; my-foo-handler gets called with:
                        ; {:event-type :foo :val 200}
  (remove-handler :foo ::bar-key)
  (event :foo :val 200) ; my-foo-handler no longer called"
  [event-type key]
  (dosync
    (doseq [handler-ref* [event-handlers* sync-event-handlers*]]
      (let [handlers (get @handler-ref* event-type {})]
        (alter handler-ref* assoc event-type (dissoc handlers key))))))

(defn remove-all-handlers
  "Remove all handlers (both sync and async) for events of type event-type."
  [event-type]
  (dosync
    (alter event-handlers* dissoc event-type)
    (alter sync-event-handlers* dissoc event-type))
  nil)

(defn- run-handler-with-known-arity [[f arity] & args]
  "Expects a tuple of fn with arity and applies fn to the first  arity number of
  args. If arity is -1 it assumes that the fn is a var and so calculates arity
  on the fly allowing for the potential rebinding of the fn with a different
  arity."
  (let [arity (if (= arity -1) (arg-count @f) arity)]
    (try
      (apply f (take arity args))
      (catch Exception e
        (log/debug "Handler Exception - got args:" args"\n"
                   (with-out-str (.printStackTrace e)))))))

(defn- handle-event
  "Runs the event handlers for the given event, and removes any handler that
  returns :done."
  [handlers* event]
  (log/debug "handling event: " event)
  (let [event-type (:event-type event)
        handlers (get @handlers* event-type {})
        _ (log/debug "handlers: " handlers)
        drop-keys (doall (map first
                              (filter (fn [[k handler]]
                                        (= :done (run-handler-with-known-arity handler event)))
                                      handlers)))]
    (dosync
      (alter handlers* assoc event-type
             (apply dissoc (get @handlers* event-type) drop-keys)))))

(defn event
  "Fire an event of type event-type with any number of additional properties.

  NOTE: an event requires key/value pairs, and everything gets wrapped into an
  event map.  It will not work if you just pass values.

  (event ::my-event)
  (event ::filter-sweep-done :instrument :phat-bass)"
  [event-type & args]
  {:pre [(even? (count args))]}
  (log/debug "event: " event-type args)
  (let [event (apply hash-map :event-type event-type args)]
    (handle-event sync-event-handlers* event)
    (if *FORCE-SYNC?*
      (handle-event event-handlers* event)
      (.execute thread-pool #(handle-event event-handlers* event)))))

(defn sync-event
  "Runs all event handlers synchronously regardless of whether they were
  declared as async or not. If handlers create new threads which generate
  events, these will revert back to the default behaviour of event (i.e. not
  forced sync). See event."
  [& args]
  (binding [*FORCE-SYNC?* true]
    (apply event args)))
