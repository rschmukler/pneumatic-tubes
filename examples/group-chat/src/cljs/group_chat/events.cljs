(ns group-chat.events
  (:require [re-frame.core :refer [reg-event-db dispatch after]]
            [group-chat.db :as db]
            [pneumatic-tubes.core :as tubes]))

(defn on-receive [event-v]
  (.log js/console "received from server:" (str event-v))
  (dispatch event-v))

(defn on-disconnect [code]
  (.log js/console "Connection with server lost. code:" code)
  (dispatch [:backend-connected false]))

(defn on-connect-failed [code]
  (.log js/console "Connection attemt failed. code: " code))

(defn on-connect []
  (.log js/console "Connected to server.")
  (dispatch [:backend-connected true]))

(def host (.-host js/location))
(def tube (tubes/tube (str "ws://" host "/chat") on-receive on-connect on-disconnect on-connect-failed))
(def send-to-server (after (fn [_ v] (tubes/dispatch tube v))))

(reg-event-db
  :initialize-db
  (fn [_ _]
    db/default-db))

(reg-event-db
  :enter-chat-room
  (fn [db [_ name room]]
    (tubes/create! tube {:name name :room room})
    (-> db
        (assoc :name name)
        (assoc :chat-room {:name     room
                           :users    []
                           :messages {}}))))

(reg-event-db
  :exit-chat-room
  (fn [_ _]
    (tubes/destroy! tube)
    db/default-db))

(reg-event-db
  :post-message
  send-to-server
  (fn [db _] db))

(reg-event-db
  :users-online-changed
  (fn [db [_ names]]
    (assoc-in db [:chat-room :users] (-> names distinct sort vec))))

(reg-event-db
  :clean-messages
  (fn [db _]
    (assoc-in db [:chat-room :messages] {})))

(reg-event-db
  :new-messages
  (fn [db [_ messages]]
    (update-in db [:chat-room :messages] into (map (fn [m] [(:db/id m) m]) messages))))

(reg-event-db
  :backend-connected
  (fn [db [_ state]]
    (assoc db :backend-connected state)))