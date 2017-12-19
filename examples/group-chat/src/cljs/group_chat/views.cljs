(ns group-chat.views
  (:require
    [reagent.ratom :refer [atom]]
    [re-frame.core :refer [dispatch subscribe]]))

(defn login-view []
  (let [name (atom "")
        chat-room (atom "")]
    (fn []
      (let [enter-disabled? (or (empty? @name) (empty? @chat-room))]
        [:div.container
         [:div.form-signin
          [:h2.form-signin-heading "Enter Chat Room"]
          [:input.form-control {:type      "text" :placeholder "Enter your nickname" :value @name
                                :on-change #(reset! name (-> % .-target .-value))}]
          [:input.form-control {:type      "text" :placeholder "Enter room name" :value @chat-room
                                :on-change #(reset! chat-room (-> % .-target .-value))}]
          [:button.btn.btn-lg.btn-primary.btn-block
           {:class    (when enter-disabled? "disabled")
            :on-click #(dispatch [:enter-chat-room @name @chat-room])} "Enter"]]]))))

(defn message-input-inner [{:keys [value enabled? on-change on-submit]}]
  [:div.input-group
   [:input.form-control {:type      "text" :placeholder "Enter Message"
                         :value     value
                         :on-change on-change}]
   [:span.input-group-btn
    [:button.btn.btn-info {:type     "button"
                           :class    (when (or (empty? value) (not enabled?)) "disabled")
                           :on-click on-submit} "SEND"]]])

(defn message-input []
  (let [text (atom "")
        backend-connected? (subscribe [:backend-connected])]
    (fn []
      [message-input-inner {:value     @text
                            :enabled?  @backend-connected?
                            :on-change #(reset! text (-> % .-target .-value))
                            :on-submit #(do
                                          (dispatch [:post-message @text])
                                          (reset! text ""))}])))

(defn chat-view [name users messages]
  [:div.container
   [:div.row
    [:h3.text-center name] [:br] [:br]
    [:div.col-md-8
     [:div.panel.panel-info
      [:div.panel-heading "RECENT CHAT HISTORY"]
      [:div.panel-body]
      [:ul.media-list
       (for [{id :db/id at :chat-message/at author :chat-message/author text :chat-message/text} messages]
         ^{:key id}
         [:li.media
          [:div.media-body
           [:div.media
            [:div.pull-left
             [:img.media-object.img-circle.user-icon {:src "assets/img/unknown.png"}]]
            [:div.media-body
             text
             [:br]
             [:small.text-muted author (str " | " at)]]]]])]]
     [:div.panel-footer
      [message-input]]]
    [:div.col-md-4
     [:div.panel.panel-primary
      [:div.panel-heading "ONLINE USERS"]
      [:div.panel-body
       [:ul.media-list
        (for [user users]
          ^{:key user}
          [:li.media
           [:div.media-body
            [:div.media
             [:div.pull-left
              [:img.media-object.img-circle.user-icon {:src "assets/img/unknown.png"}]]
             [:div.media-body
              [:h5 user]]]]])]]]
     [:button.btn.btn-primary {:on-click #(dispatch [:exit-chat-room])} "Exit"]]]])

(defn main-panel []
  (let [room-name (subscribe [:chat-room/name])
        users-online (subscribe [:chat-room/users])
        messages (subscribe [:chat-room/messages])]
    (fn []
      (if @room-name
        [chat-view @room-name @users-online @messages]
        [login-view]))))
