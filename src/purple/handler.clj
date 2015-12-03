(ns purple.handler
  (:use purple.util
        cheshire.core
        ring.util.response
        clojure.walk
        [purple.db :only [conn !select !insert !update mysql-escape-str]])
  (:require [purple.config :as config]
            [purple.users :as users]
            [purple.couriers :as couriers]
            [purple.orders :as orders]
            [purple.dispatch :as dispatch]
            [purple.coupons :as coupons]
            [purple.pages :as pages]
            [purple.analytics :as analytics]
            [purple.dashboard :as dashboard]
            [compojure.core :refer :all]
            [compojure.handler :as handler]
            [compojure.route :as route]
            [clojure.string :as s]
            [ring.middleware.cors :refer [wrap-cors]]
            [ring.middleware.ssl :refer [wrap-ssl-redirect]]
            [ring.middleware.json :as middleware]
            [ring.middleware.cookies :refer [wrap-cookies]]
            [buddy.auth.accessrules :refer [wrap-access-rules]]))


(defn wrap-page [resp]
  (header resp "Content-Type" "text/html; charset=utf-8"))

(defn wrap-xml [resp]
  (header resp "Content-Type" "text/xml; charset=utf-8"))

(defn wrap-force-ssl [resp]
  (if config/has-ssl?
    (wrap-ssl-redirect resp)
    resp))

(defmacro demand-user-auth
  [db-conn user-id token & body]
  `(if (users/valid-session? ~db-conn ~user-id ~token)
     (do ~@body)
     {:success false
      :message "Something's wrong. Please log out and log back in."}))

(defn redirect-to-app-download
  [headers]
  (redirect
   (if (.contains (str (get headers "user-agent")) "Android")
     "https://play.google.com/store/apps/details?id=com.purple.app"
     "https://itunes.apple.com/us/app/purple-services/id970824802")))

(defn valid-session-wrapper?
  "Given a request r, determine if the user-id has a valid session"
  [r]
  (let [cookies (keywordize-keys
                 ((resolve 'ring.middleware.cookies/parse-cookies) r))
        user-id (get-in cookies [:user-id :value])
        token   (get-in cookies [:token :value])]
    (users/valid-session? (conn) user-id token)))

(def rules
  [{:pattern #".*/dashboard/login" ; this route must always be allowed access
    :handler (constantly true)}
   {:pattern #".*/dashboard/logout" ; this route must always be allowed access
    :handler (constantly true)}
   {:pattern #".*/dashboard(/.*|$)"
    :handler valid-session-wrapper?
    :redirect "/dashboard/login"}])

(defroutes app-routes
  (context "/user" []
           (wrap-force-ssl
            (defroutes user-routes
              (POST "/login" {body :body
                              headers :headers
                              remote-addr :remote-addr}
                    (response
                     (let [b (keywordize-keys body)]
                       (users/login (conn)
                                    ;; 'type' is either:
                                    ;; native, facebook, or google
                                    (:type b)
                                    ;; 'platform_id' is either:
                                    ;; native:   email address
                                    ;; facebook: facebook id
                                    ;; google:   google id
                                    (:platform_id b)
                                    ;; 'auth_key' will depend on the type
                                    ;; of user it is. If it is a native user,
                                    ;; this will be their password. For Facebook
                                    ;; and Google users, this will be their
                                    ;; auth token from that platform.
                                    (:auth_key b)
                                    ;; email-override isn't checked for
                                    ;; security; could be spoofed.
                                    ;; but, currently, it's the only way we can
                                    ;; get it for Google logins on Android devices
                                    ;; because the plugin we are using doesn't allow
                                    ;; to modify scope of auth_key, but it does
                                    ;; give the email address in the JS object
                                    :email-override (:email_override b)
                                    :client-ip (or (get headers "x-forwarded-for")
                                                   remote-addr)))))
              ;; Only for native users
              (POST "/register" {body :body
                                 headers :headers
                                 remote-addr :remote-addr}
                    (response
                     (let [b (keywordize-keys body)]
                       (users/register (conn)
                                       ;; 'platform_id' is email address
                                       (:platform_id b)
                                       ;; 'auth_key' is password
                                       (:auth_key b)
                                       :client-ip (or (get headers "x-forwarded-for")
                                                      remote-addr)))))
              ;; Only for native users
              (POST "/forgot-password" {body :body}
                    (response
                     (let [b (keywordize-keys body)]
                       (users/forgot-password (conn)
                                              ;; 'platform_id' is email address
                                              (:platform_id b)))))

              ;; Only for native users
              (GET "/reset-password/:key" [key]
                   (wrap-page (response (pages/reset-password (conn) key))))
              (POST "/reset-password" {body :body}
                    (response
                     (let [b (keywordize-keys body)]
                       (users/change-password (conn) (:key b) (:password b)))))
              (POST "/edit" {body :body}
                    (response
                     (let [b (keywordize-keys body)
                           db-conn (conn)]
                       (demand-user-auth
                        db-conn
                        (:user_id b)
                        (:token b)
                        (users/edit db-conn
                                    (:user_id b)
                                    b)))))
              ;; Set up push notifications
              (POST "/add-sns" {body :body}
                    (response
                     (let [b (keywordize-keys body)
                           db-conn (conn)]
                       (demand-user-auth
                        db-conn
                        (:user_id b)
                        (:token b)
                        (users/add-sns db-conn
                                       (:user_id b)
                                       (:push_platform b)
                                       (:cred b))))))
              ;; Try a coupon code
              (POST "/code" {body :body}
                    (response
                     (let [b (keywordize-keys body)
                           db-conn (conn)]
                       (demand-user-auth
                        db-conn
                        (:user_id b)
                        (:token b)
                        (coupons/code->value db-conn
                                             (format-coupon-code (:code b))
                                             (:vehicle_id b)
                                             (:user_id b))))))
              ;; Get info about currently auth'd user
              (POST "/details" {body :body}
                    (response
                     (let [b (keywordize-keys body)
                           db-conn (conn)]
                       (demand-user-auth
                        db-conn
                        (:user_id b)
                        (:token b)
                        (users/details db-conn
                                       (:user_id b)
                                       :user-meta {:app_version (:version b)
                                                   :os (:os b)}))))))))
  (context "/orders" []
           (wrap-force-ssl
            (defroutes orders-routes
              (POST "/add" {body :body}
                    (response
                     (let [b (keywordize-keys body)
                           db-conn (conn)]
                       (demand-user-auth
                        db-conn
                        (:user_id b)
                        (:token b)
                        (orders/add db-conn
                                    (:user_id b)
                                    (:order b))))))
              (POST "/rate" {body :body}
                    (response
                     (let [b (keywordize-keys body)
                           db-conn (conn)]
                       (demand-user-auth
                        db-conn
                        (:user_id b)
                        (:token b)
                        (orders/rate db-conn
                                     (:user_id b)
                                     (:order_id b)
                                     (:rating b))))))
              ;; Courier updates status of order (e.g., Enroute -> Servicing)
              (POST "/update-status-by-courier" {body :body}
                    (response
                     (let [b (keywordize-keys body)
                           db-conn (conn)]
                       (demand-user-auth
                        db-conn
                        (:user_id b)
                        (:token b)
                        (orders/update-status-by-courier db-conn
                                                         (:user_id b)
                                                         (:order_id b)
                                                         (:status b))))))
              ;; Customer tries to cancel order
              (POST "/cancel" {body :body}
                    (response
                     (let [b (keywordize-keys body)
                           db-conn (conn)]
                       (demand-user-auth
                        db-conn
                        (:user_id b)
                        (:token b)
                        (orders/cancel db-conn
                                       (:user_id b)
                                       (:order_id b)))))))))
  (context "/dispatch" []
           (wrap-force-ssl
            (defroutes dispatch-routes
              ;; Get current gas price
              (POST "/gas-prices" {body :body}
                    (response
                     (let [b (keywordize-keys body)]
                       (dispatch/get-gas-prices (:zip_code b)))))
              ;; Check availability options for given params (location, etc.)
              (POST "/availability" {body :body}
                    (response
                     (let [b (keywordize-keys body)
                           db-conn (conn)]
                       (demand-user-auth
                        db-conn
                        (:user_id b)
                        (:token b)
                        (dispatch/availability db-conn
                                               (:zip_code b)
                                               (:user_id b)))))))))
  (context "/courier" []
           (wrap-force-ssl
            (defroutes courier-routes
              ;; Courier app periodically updates web service with their status
              (POST "/ping" {body :body}
                    (response
                     (let [b (keywordize-keys body)
                           db-conn (conn)]
                       (demand-user-auth
                        db-conn
                        (:user_id b)
                        (:token b)
                        (dispatch/courier-ping db-conn
                                               (:user_id b)
                                               (if (nil? (:lat b))
                                                 0
                                                 (unless-p
                                                  Double/isNaN
                                                  (Double. (:lat b)) 0))
                                               (if (nil? (:lng b))
                                                 0
                                                 (unless-p
                                                  Double/isNaN
                                                  (Double. (:lng b)) 0))
                                               (or (:gallons b) 0)))))))))
  (context "/feedback" []
           (wrap-force-ssl
            (defroutes feedback-routes
              (POST "/send" {body :body}
                    (response
                     (let [b (keywordize-keys body)
                           db-conn (conn)]
                       ;; they don't have to send user auth
                       ;; but if they do, it should be correct
                       (if-not (nil? (:user_id b))
                         (demand-user-auth
                          db-conn
                          (:user_id b)
                          (:token b)
                          (send-feedback (:text b)
                                         :user_id (:user_id b)))
                         (send-feedback (:text b)))))))))
  (context "/invite" []
           (wrap-force-ssl
            (defroutes invite-routes
              ;; I don't think this is being used anymore.
              ;; But keep it for a while because of old versions of app.
              (POST "/send" {body :body}
                    (response
                     (let [b (keywordize-keys body)
                           db-conn (conn)]
                       ;; they don't have to send user auth
                       ;; but if they do, it should be correct
                       (if-not (nil? (:user_id b))
                         (demand-user-auth
                          db-conn
                          (:user_id b)
                          (:token b)
                          (users/send-invite db-conn
                                             (:email b)
                                             :user_id (:user_id b)))
                         (users/send-invite db-conn
                                            (:email b)))))))))
  (context "/dashboard" []
           (wrap-force-ssl
            (defroutes dashboard-routes
              (GET "/" []
                   (-> (pages/dashboard (conn))
                       response
                       wrap-page))
              (GET "/login" []
                   (-> (pages/dash-login)
                       response
                       wrap-page))
              (POST "/login" {body :body
                              headers :headers
                              remote-addr :remote-addr}
                    (response
                     (let [b (keywordize-keys body)]
                       (dashboard/login (conn) (:email b) (:password b)
                                        (or (get headers "x-forwarded-for")
                                            remote-addr)))))
              (GET "/logout" []
                   (-> (redirect "/dashboard/login")
                       (set-cookie "token" "null" {:max-age -1})
                       (set-cookie "user-id" "null" {:max-age -1})))
              (GET "/all" []
                   (-> (pages/dashboard (conn) :all true)
                       response
                       wrap-page))
              (GET "/declined" []
                   (-> (pages/declined (conn))
                       response
                       wrap-page))
              (GET "/generate-stats-csv" []
                   (do (future (analytics/gen-stats-csv))
                       (response {:success true})))
              (GET "/download-stats-csv" []
                   (-> (response (java.io.File. "stats.csv"))
                       (header "Content-Type:"
                               "text/csv; name=\"stats.csv\"")
                       (header "Content-Disposition"
                               "attachment; filename=\"stats.csv\"")))
              (POST "/send-push-to-all-active-users" {body :body}
                    (response
                     (let [b (keywordize-keys body)]
                       (pages/send-push-to-all-active-users (conn)
                                                            (:message b)))))
              (POST "/send-push-to-users-list" {body :body}
                    (response
                     (let [b (keywordize-keys body)]
                       (pages/send-push-to-users-list (conn)
                                                      (:message b)
                                                      (:user-ids b)))))
              ;; Dashboard admin cancels order
              (POST "/cancel-order" {body :body}
                    ;; cancel the order
                    (response
                     (let [b (keywordize-keys body)
                           db-conn (conn)]
                       (orders/cancel
                        db-conn
                        (:user_id b)
                        (:order_id b)
                        :origin-was-dashboard true
                        :notify-customer true
                        :suppress-user-details true
                        :override-cancellable-statuses
                        (conj config/cancellable-statuses "servicing")))))
              ;; admin updates status of order (e.g., Enroute -> Servicing)
              (POST "/update-status" {body :body}
                    (response
                     (let [b (keywordize-keys body)
                           db-conn (conn)]
                       (orders/update-status-by-admin db-conn
                                                      (:order_id b)))))
              ;; admin assigns courier to an order
              (POST "/assign-order" {body :body}
                    (response
                     (let [b (keywordize-keys body)
                           db-conn (conn)]
                       (orders/assign-to-courier-by-admin db-conn
                                                          (:order_id b)
                                                          (:courier_id b)))))
              ;; update a zones description. Currently only supports
              ;; updating fuel_prices, service_fees and service_time_bracket
              (POST "/update-zone" {body :body}
                    (response
                     (let [b (keywordize-keys body)
                           db-conn (conn)]
                       (dispatch/update-zone! db-conn
                                              (:id b)
                                              (:fuel_prices b)
                                              (:service_fees b)
                                              (:service_time_bracket b)))))
              ;; update a courier's assigned zones
              (POST "/update-courier-zones" {body :body}
                    (response
                     (let [b (keywordize-keys body)
                           db-conn (conn)]
                       (users/update-courier-zones!
                        db-conn
                        (:id b)
                        (:zones b)))))
              (GET "/dash-map-orders" []
                   (-> (pages/dash-map :callback-s
                                       "dashboard_cljs.core.init_map_orders")
                       response
                       wrap-page))
              (GET "/dash-map-couriers" []
                   (-> (pages/dash-map :callback-s
                                       "dashboard_cljs.core.init_map_couriers")
                       response
                       wrap-page))
              ;; given a date in the format YYYY-MM-DD, return all orders
              ;; that have occurred since then
              (POST "/orders-since-date"  {body :body}
                    (response
                     (let [b (keywordize-keys body)
                           db-conn (conn)]
                       (orders/orders-since-date-with-supplementary-data
                        db-conn (:date b)))))
              ;; return all couriers
              (POST "/couriers" {body :body}
                    (response
                     (let [b (keywordize-keys body)
                           db-conn (conn)]
                       {:couriers (->> (couriers/all-couriers db-conn)
                                       (users/include-user-data db-conn))})))
              ;; return ZCTA defintions for zips
              (POST "/zctas" {body :body}
                    (response
                     (let [b (keywordize-keys body)
                           db-conn (conn)]
                       {:zctas
                        (dispatch/get-zctas-for-zips db-conn (:zips b))})))
              ;; return all zones
              (POST "/zones" {body :body}
                    (response
                     {:zones (dispatch/get-all-zones-from-db (conn))}))
              )))
  (context "/twiml" []
           (defroutes twiml-routes
             (POST "/courier-new-order" []
                   (-> (pages/twiml-simple config/delayed-assignment-message)
                       response
                       wrap-xml))))
  (GET "/download" {headers :headers}
       (redirect-to-app-download headers))
  (GET "/app" {headers :headers}
       (redirect-to-app-download headers))
  (GET "/terms" [] (wrap-page (response (pages/terms))))
  (GET "/ok" [] (response {:success true}))
  (GET "/" [] (wrap-page (response (pages/home))))
  (route/resources "/")
  (route/not-found (wrap-page (response (pages/not-found-page)))))

(def app
  (-> (handler/site app-routes)
      (wrap-cors :access-control-allow-origin [#".*"]
                 :access-control-allow-methods [:get :put :post :delete])
      (wrap-access-rules {:rules rules})
      (wrap-cookies)
      (middleware/wrap-json-body)
      (middleware/wrap-json-response)))
