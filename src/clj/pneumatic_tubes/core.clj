(ns pneumatic-tubes.core
  (:use [clojure.tools.logging :only [info warn error]]
        [clojure.set])
  (:require [clojure.core.async :refer [<! >! go go-loop chan]]))

;; -------- tube-registry-------------------------------------------------------------------

(def ^:private tube-registry (atom {:tubes    {}
                                    :send-fns {}}))

(defn- next-tube-data [current-data new-data]
  (if (map? new-data)
    (assoc new-data :tube/id (:tube/id current-data))
    current-data))

(defn- update-tube-data
  [registry tube-id new-data]
  (let [current-data (get-in registry [:tubes tube-id])]
    (if current-data
      (assoc-in registry [:tubes tube-id] (next-tube-data current-data new-data))
      registry)))

(defn- add-tube [registry tube-id send-fn initial-data]
  (-> registry
      (assoc-in [:tubes tube-id] {:tube/id tube-id})
      (assoc-in [:send-fns tube-id] send-fn)
      (update-tube-data tube-id initial-data)))

(defn- rm-tube [registry tube-id]
  (-> registry
      (update :tubes dissoc tube-id)
      (update :send-fns dissoc tube-id)))

(defn- criteria-by-tube-id [id]
  (fn [tube]
    (or (= (:tube/id tube) id)
        (= (:tube/id tube) (:tube/id id)))))

(defn- find-tubes-by-criteria [registry criteria]
  (let [all-tubes (vals (:tubes registry))]
    (if (= criteria :all)
      all-tubes
      (if (fn? criteria)
        (filter criteria all-tubes)
        (find-tubes-by-criteria registry (criteria-by-tube-id criteria))))))

(defn add-tube!
  "Registers a new tube in a global registry,
  the send-fn is a function which sends a message via implementation-specific channel like a WebSocket"
  ([send-fn]
   (add-tube! send-fn {}))
  ([send-fn client-data]
   (let [tube-id (java.util.UUID/randomUUID)]
     (swap! tube-registry #(add-tube % tube-id send-fn client-data))
     tube-id)))

(defn update-tube-data!
  "Associates the some data with the tube.
  This is like putting a sticker with a label on a tube,
  so that you can select the tube by label to send messages to particular destination"
  [tube-id new-data]
  (swap! tube-registry #(update-tube-data % tube-id new-data))
  tube-id)

(defn get-tube [id]
  "Returns current tube data from rergistry"
  (get-in @tube-registry [:tubes id]))

(defn find-tubes [criteria]
  "Returns current tube data from rergistry"
  (find-tubes-by-criteria @tube-registry criteria))

(defn rm-tube!
  "Removes tube from the registry"
  [tube-id]
  (swap! tube-registry #(rm-tube % tube-id))
  tube-id)


;; -------- receiver ----------------------------------------------------------------------

(defn- lookup-handler
  [receiver event-id]
  (get (:event-handlers receiver) event-id))

(defn- handle-incoming
  [receiver {from :from event-v :event}]
  (try
    (let [event-id (first event-v)
          tube-id (:tube/id from)
          handler-fn (lookup-handler receiver event-id)]
      (if (nil? handler-fn)
        (error "pneumatic-tubes: no event handler registered for: \"" event-id "\". Ignoring.")
        (update-tube-data! tube-id (handler-fn from event-v))))
    (catch Throwable e (error "pneumatic-tubes: Exception while processing event:"
                              event-v "received from tube" from e))))

(defn- noop-handler [tube _] tube)
(def ^:private noop-handlers {:tube/on-create  noop-handler
                              :tube/on-destroy noop-handler})

(defn receiver
  "Receiver processes all the messages coming to him using the provided handler map.
  Handler map key is an event-id and valye is function to process the event."
  ([handler-map] (receiver (chan 100) handler-map))
  ([in-queue handler-map]
   (let [this {:in-queue       in-queue
               :event-handlers (into noop-handlers handler-map)}]
     (go-loop [event (<! in-queue)]
       (when event
         (handle-incoming this event)
         (recur (<! in-queue))))
     this)))

(defn receive
  "Asynchronously process the incoming event"
  ([receiver from event-v]
   (go (>! (:in-queue receiver) {:from from :event event-v}))))

(defn wrap-handlers
  "Wraps a map of handlers with one or more middlewares"
  ([handler-map middleware & middlewares]
   (let [kv-pairs (seq handler-map)
         wrapped-map (into {} (map (fn [[k v]] [k (middleware v)]) kv-pairs))]
     (if (empty? middlewares)
       wrapped-map
       (apply wrap-handlers wrapped-map (first middlewares) (rest middlewares))))))

;; -------- transmitter ----------------------------------------------------------------------

(defn dispatch
  "Send event vector to one or more tubes.
  Destination (parameter 'to') can be a map, a predicate function or :all keyword.
  The event vector (parameter event) ca be either a vector or a function which takes a tube and returns event vector"
  ([transmitter to event]
   (let [out-chan (:out-queue transmitter)
         target-tubes (find-tubes-by-criteria @tube-registry to)
         event-provider (if (fn? event) event (fn [_] event))]
     (go (doseq [tube target-tubes]
           (>! out-chan {:to (:tube/id tube) :event (event-provider tube)}))))
   to))

(defn- send-to-tube [tube-registry tube-id event-v]
  (let [send! (get-in tube-registry [:send-fns tube-id])]
    (send! event-v)))

(defn- handle-outgoing
  [tube-registry {tube-id :to event-v :event}]
  (send-to-tube tube-registry tube-id event-v))

(defn- call-listeners [on-send {to :to event-v :event}]
  (when on-send
    (if (coll? on-send)
      (doseq [on-send-fn on-send]
        (on-send-fn to event-v))
      (on-send to event-v))))

(defn transmitter
  "Transmitter is responsible for sending events."
  ([] (transmitter (chan 100) nil))
  ([on-send] (transmitter (chan 100) on-send))
  ([out-queue on-send]
   (let [this {:out-queue      out-queue
               :send-listeners on-send}]
     (go-loop [event (<! out-queue)]
       (when event
         (try
           (handle-outgoing @tube-registry event)
           (call-listeners on-send event)
           (catch Exception e (error "pneumatic-tubes: Exception while transmitting event:" event e)))
         (recur (<! out-queue))))
     this)))
