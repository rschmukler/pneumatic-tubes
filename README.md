# pneumatic-tubes

WebSocket based transport of events between re-frame app and server

[![Clojars Project](https://img.shields.io/clojars/v/pneumatic-tubes.svg)](https://clojars.org/pneumatic-tubes)
[![Build Status](https://travis-ci.org/drapanjanas/pneumatic-tubes.svg?branch=master)](https://travis-ci.org/drapanjanas/pneumatic-tubes)

## Why

While it is quite easy to [talk to servers](https://github.com/Day8/re-frame/wiki/Talking-To-Servers)
in a re-frame app, imagine if you could just throw `[:your-event data]` from client directly to server,
process it, and dispatch one or more `[:server-side-event with data]` back to one or more client re-frame apps?
I think this is a very intuitive way of building real time features in your re-frame app.

So, the idea of pneumatic-tubes:
* Define handlers for some of your re-frame events on server.
* Dispatch events to server via a WebSocket connection channel.
* Dispatch events from server to one or more clients.
* Mark WebSocket channels with some 'label' data, and use it for selective dispatching events to multiple clients

I call one WebSocket channel - a tube, because of the idea that you can put your labels
on it and then select tubes using is labels for dispatching events to clients.

## Usage

### Server

```clojure
(:use org.httpkit.server)
(:require [pneumatic-tubes.core :refer [receiver transmitter dispatch]]
          [pneumatic-tubes.httpkit :refer [websocket-handler]])

(def tx (transmitter))          ;; responsible for transmitting messages to one or more clients
(def dispatch-to (partial dispatch tx)) ;; helper function to dispatch using this transmitter 

(def rx                         ;; collection of handlers for processing incoming messages
  (receiver
    {:say-hello                 ;; re-frame event name
     (fn [tube [_ name]]        ;; re-frame event handler function
       (println "Hello" name)
       (dispatch-to tube [:say-hello-processed])  ;; send event to same 'tube' where :say-hello came from
       from)}))

(def handler (websocket-handler rx))   ;; kttp-kit based WebSocket request handler
                                       ;; it also works with Compojure routes

(run-server handler {:port 9090})
```
#### Server side event handler
The event handler on server has similar signature as in re-frame app.
```clojure
(fn [source-tube [event-name param1 param2 ...]]

    (event-handling-logic)

    (assoc source-tube :my-label "value")))
```
The important difference is that instead of app-db you are getting a data map associated with
the source tube of the event. This data is something like a 'label' placed on the 'tube'.
Event handler must return the new data from the event, it will be associated with the tube.

This 'label' data intended to be used for filtering tubes when you dispatching events to multiple clients.

#### Wrapping handlers with middleware
You can share some cross-cutting logic with handlers using custom middleware.

Middleware is function which returns a handler decorated with some additional logic.

Lets say you want to print all received events:
```clojure
(:require [pneumatic-tubes.core :as tubes]])

(def handlers {...})                                                     ;; initial handlers

(defn trace-middleware [handler]
  (fn [tube event-v]
    (println "Received event" event-v "from" tube)
    (handler tube event-v)))                                             ;; call wrapped handler

(def wrapped-handlers (tubes/wrap-handlers handlers trace-middleware))   ;; decorated handlers

(def rx (receiver wrapped-handlers))                                     ;; pass handlers to receiver
```
For more interesting example have a look at Datomic transaction middleware in
[Group chat example app](https://github.com/drapanjanas/pneumatic-tubes/blob/9c24f4de046977af9614201d84770569e95b0622/examples/group-chat/src/clj/group_chat/core.clj#L14-L23)

#### Dispatching server events to clients
In your server side code you can dispatch (push) events to your client apps using `dispatch` function.
```clojure
(dispatch transmitter target-tube [:my-event with paramaters])
```
It takes more parameters than dispatch in re-rfame mainly because you need to specify the destination.

Destination is specified in 2nd parameter, you can use:

1. keyword :all to send event to all connected clients

    ```clojure
    (dispatch transmitter :all [:my-event with params])
    ```
1. a map containing entry key :tube/id, to dispatch to a single tube

    ```clojure
    (dispatch transmitter soruce-tube [:my-event with params])
    ```
1. a predicate function of 'label' data which will be used as filtering criteria for target tubes

    ```clojure
    (dispatch transmitter #(= (:some-prop %) "value") [:my-event with params])
    ```

#### Purpose of transmitter
The intention of having more transmitter is to be able implementing some clustering strategy of your choice.
You can add an 'on-send' hook to the transmitter, and broadcast the event to some other destinations (like to other nodes of cluster)
Read more about that in Clustering section of this README

### Client

```clojure
(:require [re-frame.core :refer [reg-event-db dispatch after]]
          [pneumatic-tubes.core :as tubes]

(defn on-receive [event-v]                                        ;; handler of incoming events from server
  (.log js/console "received from server:" (str event-v))
  (re-frame/dispatch event-v))

(def tube (tubes/tube (str "ws://localhost:9090/ws") on-receive)) ;; definition of event 'tube' over WebSocket
(def send-to-server (after (fn [_ v] (tubes/dispatch tube v))))   ;; middleware to send event to server
                                                                  ;; after it is processed on client

(reg-event-db                                   ;; normal re-frame handler
  :say-hello
  send-to-server                                ;; forwards this event also to server
  (fn [db [_ name]]
    (.log js/console (str "Hello " name))
    db))

(reg-event-db                                   ;; will be called by server
  :say-hello-processed
  (fn [db _]
    (.log js/console "Yay!!!")
    db))

(tubes/create! tube)
```

#### The tube on client
A WebSocket channel (which I call 'tube') in client app is specified like this:
```clojure
(def tube (tubes/tube (str "ws://localhost:9090/ws") #(re-frame/dispatch %))
```
No connection is done at this moment - this is only defines the url and the on-receive handler.
Once the tube is defined you can create or destroy it at some point.

As pneumatic-tubes has no dependency on re-frame (just influenced by it)
so at minimum your on-receive handler should just dispatch a re-frame event line in example above.

To make a real WebSocket connection:
```clojure
 (tubes/create! tube)
```
You can pass additional parameters (like a web token) like this:
```clojure
 (tubes/create! tube {:token "abc"})
```
This will establish WS connection using url: `ws://localhost:9090/ws?token=abc`.
When tube is created, event `:tube/on-create` will be published on server.

To destroy the tube at any time call:
```clojure
 (tubes/destroy! tube)
```
Event `:tube/on-destroy` will be published on server.

#### Connection error handling
By default tube will try to reconnect to server using default backoff strategy
which is random number of milliseconds with linearly growing max limit.

To react on the loss of connection you can pass `on-connect` and `on-diconnect` hooks of tube:
```clojure
(defn on-disconnect []
      (.log js/console "Connection with server lost.")
      (re-frame/dispatch [:disable-some-features]))

(defn on-connect []
      (re-frame/dispatch [:enable-some-features]))

(def tube (tubes/tube url on-receive on-connect on-disconnect))
```

#### Dispatching events to server
To dispatch event at any point use:
```clojure
 (tubes/dispatch tube [:server-side-event with data])
```
In case you want just forward some re-frame app event to server you can use a middleware:
```
(def send-to-server (tubes/send-to-tube-middleware tube))

(re-frame/register-handler
  :some-event
  send-to-server
  (fn [db [_ name]]
    (do-some-optimistic-logic-with db)))
```
The same event will be sent to server right after the re-frame handler is finished.

## Examples
* [Average number](https://github.com/drapanjanas/pneumatic-tubes/tree/master/examples/average-number) -
very basic usage example
* [Group chat](https://github.com/drapanjanas/pneumatic-tubes/tree/master/examples/group-chat) -
more complex example which uses most of the features of pneumatic-tubes also demonstrates Datomic usage.

## Applications
* [Commutewise](https://commutewise.com) - social network for commute ridesharing is havily using pneumatic-tubes instead of REST services 

## What to do next
* Add tests
* Example with some clustering solution

## License

Copyright © 2016 Artūr Girenko

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
