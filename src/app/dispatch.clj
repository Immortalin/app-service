(ns app.dispatch
  (:require [common.db :refer [conn !select !insert !update]]
            [common.config :as config]
            [common.util :refer [! cents->dollars-str five-digit-zip-code
                                 get-event-time in? minute-of-day->hmma
                                 now-unix only-prod only-prod-or-dev
                                 segment-client send-sms split-on-comma
                                 unix->minute-of-day log-error catch-notify
                                 unix->day-of-week]]
            [common.orders :as orders]
            [common.users :as users]
            [common.zones :refer [get-fuel-prices get-service-fees
                                  get-service-time-bracket
                                  get-zone-by-zip-code order->zone-id
                                  zip-in-zones?]]
            [common.subscriptions :as subscriptions]
            [ardoq.analytics-clj :as segment]
            [clojure.algo.generic.functor :refer [fmap]]
            [clojure.java.jdbc :as sql]
            [clojure.string :as s]
            [clojure.walk :refer [keywordize-keys stringify-keys]]
            [overtone.at-at :as at-at]
            [app.couriers :as couriers]
            [app.orders :refer [get-all-current]]
            [app.users :refer [call-user]]
            [opt.planner :refer [compute-suggestion]]))

(defn get-gas-prices
  "Given a zip-code, return the gas prices."
  [zip-code]
  (if (zip-in-zones? zip-code)
    {:success true
     :gas_prices (get-fuel-prices zip-code)}
    {:success false
     :message "Location Outside Service Area"
     ;; to make legacy app versions happy
     :gas_prices {:87 0 :91 0}}))

(defn delivery-time-map
  [time-str service-fee num-free num-free-used sub-discount]
  (let [fee-str #(if (= % 0) "free" (str "$" (cents->dollars-str %)))
        gen-text #(str time-str " (" % ")")]
    (if (not (nil? num-free)) ;; using a subscription?
      (let [num-free-left (- num-free num-free-used)]
        (if (pos? num-free-left)
          {:service_fee 0
           :text (gen-text (if (< num-free-left 1000)
                             (str num-free-left " left")
                             (fee-str 0)))}
          (let [after-discount (max 0 (+ service-fee sub-discount))]
            {:service_fee after-discount
             :text (gen-text (fee-str after-discount))})))
      {:service_fee service-fee
       :text (gen-text (fee-str service-fee))})))

(defn delivery-times-map
  "Given subscription usage map and service fee, create the delivery-times map."
  [user zip-def sub service-fees]
  (let [has-free-three-hour? (pos? (or (:num_free_three_hour sub) 0))
        has-free-one-hour? (pos? (or (:num_free_one_hour sub) 0))]

    (->> (remove #(or (and (= 300 (val %)) ;; hide 5-hour option if using 1-hour or 3-hour subscription
                           (or has-free-three-hour? has-free-one-hour?))
                      (and (= 180 (val %)) ;; hide 3-hour option if using 1-hour subscription
                           (or has-free-one-hour?)))
                 (:time-choices zip-def))
         (#(for [[k v] %]
             [v (assoc (cond
                         (= 300 v) (delivery-time-map "within 5 hours"
                                                      (get service-fees v)
                                                      (:num_free_five_hour sub)
                                                      (:num_free_five_hour_used sub)
                                                      (:discount_five_hour sub))
                         (= 180 v) (delivery-time-map "within 3 hours"
                                                      (get service-fees v)
                                                      (:num_free_three_hour sub)
                                                      (:num_free_three_hour_used sub)
                                                      (:discount_three_hour sub))
                         (= 60 v) (delivery-time-map "within 1 hour"
                                                     (get service-fees v)
                                                     (:num_free_one_hour sub)
                                                     (:num_free_one_hour_used sub)
                                                     (:discount_one_hour sub))
                         (= 30 v) (delivery-time-map "within 30 minutes"
                                                     (get service-fees v)
                                                     (:num_free_half_hour sub)
                                                     (:num_free_half_hour_used sub)
                                                     (:discount_half_hour sub)))
                       :order (Integer. (name k)))]))
         (into {}))))

(defn num-couriers-connected-in-market
  "How many couriers are currently connected and on duty in the market that this
  zone is in?"
  [zone-id]
  (->> (couriers/get-all-connected (conn))
       (couriers/filter-by-market (quot zone-id 50))
       count))

(defn enough-couriers?
  "Does the market that this ZIP is in have enough couriers to offer service?"
  [zip-code subscription]
  (let [zone-id (:id (get-zone-by-zip-code zip-code))]
    (or (in? [0 2 3] (quot zone-id 50)) ;; LA, OC, SEA are exempt from this constraint
        (and (not (nil? (:id subscription))) ;; subscribers are exempt
             (not= (:id subscription) 0))
        (pos? (num-couriers-connected-in-market zone-id)))))

;; TODO this function should consider if a zone is actually "active"
(defn available
  [user good-time? zip-def subscription enough-couriers-delay octane]
  {:octane octane
   :gallon_choices (if (users/is-managed-account? user)
                     {:0 7.5
                      :1 10
                      :2 15
                      :3 20
                      :4 25
                      :5 30}
                     (:gallon-choices zip-def))
   :default_gallon_choice (:default-gallon-choice zip-def)
   :price_per_gallon (get (:gas-price zip-def) octane)
   :times (->> (:delivery-fee zip-def)
               (delivery-times-map user zip-def subscription)
               (filter (fn [[time time-map]]
                         (and good-time?
                              @enough-couriers-delay)))
               (into {}))
   ;; default_time_choice not implemented in app yet
   :default_time_choice (:default-gallon-choice zip-def)
   :tire_pressure_check_price (:tire-pressure-price zip-def)
   ;; for legacy app versions (< 1.2.2)
   :gallons 15})

(defn hours-today
  [hours]
  (nth hours (dec (unix->day-of-week (now-unix)))))

(defn get-market-def-by-zip
  [zip-code]
  (let [markets [{:name "Los Angeles"
                  :gallon-choices {:0 7.5
                                   :1 10
                                   :2 15}
                  :default-gallon-choice :2 ; NOTE, use key
                  :gas-price {"87" 305
                              "91" 329}
                  :time-choices {:0 60
                                 :1 180
                                 :2 300}
                  :default-time-choice 180 ; NOTE, use value, not key here
                  :delivery-fee {30 999
                                 60 599
                                 180 399
                                 300 299}
                  :tire-pressure-price 700
                  :hours [[[450 1350]]
                          [[450 1350]]
                          [[450 1350]]
                          [[450 1350]]
                          [[450 1350]]
                          [[450 1350]]
                          [[450 1350]]]
                  :closed-message nil
                  :manually-closed? false
                  :zips {"90210" {:gas-price-diff-fixed {"87" 4}}
                         "90025" {}
                         "90024" {:gallon-choices {:0 7.5
                                                   :1 10
                                                   :2 15
                                                   :3 20}
                                  :default-gallon-choice :3
                                  :gas-price-diff-percent {"87" 1.5}
                                  :gas-price-diff-fixed {"87" 2
                                                         "91" 5}
                                  :time-choices {:0 60
                                                 :1 180
                                                 :2 30}
                                  :tire-pressure-price 500
                                  :delivery-fee-diff-percent {60 0
                                                              180 0
                                                              300 0}
                                  :delivery-fee-diff-fixed {60 20
                                                            180 25
                                                            300 0}
                                  :manually-closed? false}
                         "90026" {:gas-price-diff-percent {"87" 0
                                                           "91" 0}
                                  :gas-price-diff-fixed {"87" 0
                                                         "91" 0}
                                  :delivery-fee-diff-percent {60 0
                                                              180 0
                                                              300 0}
                                  :delivery-fee-diff-fixed {60 0
                                                            180 0
                                                            300 0}
                                  ;; closed on weekend
                                  :hours [[[450 1350]]
                                          [[450 1350]]
                                          [[450 1350]]
                                          [[450 1350]]
                                          [[450 1350]]
                                          []
                                          []]
                                  :manually-closed? false
                                  :closed-message "Sorry, this ZIP closed for no reason."}
                         "90023" {:manually-closed? true
                                  :closed-message "On-demand service is not available at this location. If you would like to arrange for Scheduled Delivery, please contact: orders@purpleapp.com to coordinate your service."
                                  }}}]]
    (first (filter #(contains? (:zips %) zip-code) markets))))

(defn trans-def
  "Transform a definition. (e.g., market definition with ZIP specific rules)."
  [base trans]
  (let [hours (or (:hours trans) (:hours base))]
    {:gallon-choices
     (or (:gallon-choices trans) (:gallon-choices base))
     
     :default-gallon-choice
     (or (:default-gallon-choice trans) (:default-gallon-choice base))
     
     :gas-price
     (into {}
           (for [[k v] (:gas-price base)
                 :let [diff-percent (or (get (:gas-price-diff-percent trans) k) 0)
                       diff-fixed (or (get (:gas-price-diff-fixed trans) k) 0)]]
             [k (Math/round (+ (double v)
                               (* v (/ diff-percent 100))
                               diff-fixed))]))
     
     :time-choices
     (or (:time-choices trans) (:time-choices base))
     
     :default-time-choice
     (or (:default-time-choice trans) (:default-time-choice base))
     
     :delivery-fee
     (into {}
           (for [[k v] (:delivery-fee base)
                 :let [diff-percent (or (get (:delivery-fee-diff-percent trans) k) 0)
                       diff-fixed (or (get (:delivery-fee-diff-fixed trans) k) 0)]]
             [k (Math/round (+ (double v)
                               (* v (/ diff-percent 100))
                               diff-fixed))]))
     
     :tire-pressure-price
     (or (:tire-pressure-price trans) (:tire-pressure-price base))

     :hours hours
     
     :closed-message
     (or (:closed-message trans)
         (:closed-message base)
         ;; TODO needs to handle empty hours better. maybe:
         ;; "Sorry, this ZIP is closed on Saturday and Sunday."
         (str "Sorry, today's service hours for this ZIP code are "
              (->> (hours-today hours)
                   (map #(str (minute-of-day->hmma (first %))
                              " to "
                              (minute-of-day->hmma (second %))))
                   (interpose " and ")
                   (apply str))
              ". Thank you for your business."))

     :manually-closed?
     (or (:manually-closed? base) (:manually-closed? trans))}))

(defn get-zip-def
  [zip-code]
  (when-let [market-def (get-market-def-by-zip zip-code)]
    (trans-def market-def (get (:zips market-def) zip-code))))

(defn is-open-now?
  [zip-def]
  (let [now-minute (unix->minute-of-day (now-unix))]
    (and (not (:manually-closed? zip-def))
         (some #(<= (first %) now-minute (second %))
               (hours-today (:hours zip-def))))))

(defn availabilities-map
  [zip-code user subscription]
  (if-let [zip-def (get-zip-def zip-code)] ; do we service this ZIP code at all?
    (let [enough-couriers-delay (delay (enough-couriers? zip-code subscription))]
      {:availabilities (map (partial available
                                     user
                                     (is-open-now? zip-def)
                                     zip-def
                                     subscription
                                     enough-couriers-delay)
                            ["87" "91"])
       ;; if unavailable (mobile app client will determine from :availabilities)
       :unavailable-reason
       (cond
         (not (is-open-now? zip-def))
         (do (only-prod-or-dev
              (segment/track segment-client (:id user) "Availability Check Said Unavailable"
                             {:address_zip zip-code
                              :reason "outside-service-hours-or-closed"}))
             (:closed-message zip-def))

         (not @enough-couriers-delay)
         (do (only-prod-or-dev
              (segment/track segment-client (:id user) "Availability Check Said Unavailable"
                             {:address_zip zip-code
                              :reason "no-couriers-available"}))
             (str "We are busy. There are no couriers available. Please "
                  "try again later."))

         ;; it's available, no unavailable-reason needed
         :else "")})
    ;; we don't service this ZIP code
    {:availabilities [{:octane "87" :gallons 15 :times {} :price_per_gallon 0}
                      {:octane "91" :gallons 15 :times {} :price_per_gallon 0}]
     :unavailable-reason
     (do (only-prod-or-dev
          (segment/track segment-client (:id user) "Availability Check Said Unavailable"
                         {:address_zip zip-code
                          :reason "outside-service-area"}))
         (str "Sorry, we are unable to deliver gas to your "
              "location. We are rapidly expanding our service "
              "area and hope to offer service to your "
              "location very soon."))}))

(defn availability
  "Get an availability map to tell client what orders it can offer to user."
  [db-conn zip-code-any-length user-id]
  (let [user (users/get-user-by-id db-conn user-id)
        subscription (when (subscriptions/valid-subscription? user)
                       (subscriptions/get-with-usage db-conn user))
        user-info ;; user details to include response
        (merge (select-keys user users/safe-authd-user-keys)
               {:subscription_usage subscription})
        system-info ;; system details to include in response
        {:referral_referred_value config/referral-referred-value
         :referral_referrer_gallons config/referral-referrer-gallons
         :subscriptions
         (into {} (map (juxt :id identity)
                       (!select db-conn "subscriptions" ["*"] {})))}
        zip-code (five-digit-zip-code zip-code-any-length)]
    (only-prod-or-dev
     (segment/track segment-client user-id "Availability Check"
                    {:address_zip zip-code}))
    (merge {:success true
            :user user-info
            :system system-info}
           ;; merge in :availabilities & :unavailable-reason
           (availabilities-map zip-code user subscription))))

;; (do (println "-------========-------")
;;     (clojure.pprint/pprint
;;      (:availabilities (availability (common.db/conn) "90025" "9kaU0GW1aJ4wF94tLGVc")))
;;     (println "-------========-------"))

(defn update-courier-state
  "Marks couriers as disconnected as needed."
  [db-conn]
  (let [expired-couriers (->> (couriers/get-all-expired db-conn)
                              (users/include-user-data db-conn))]
    (when-not (empty? expired-couriers)
      (only-prod (run! ;; notify all courier that got disconnected but are 'on_duty'
                  #(send-sms
                    (:phone_number %)
                    (str "You've been disconnected from the Purple Courier app. "
                         "This can happen if you are in an area with a poor "
                         "internet connection. You may need to close the app and "
                         "re-open it. If the problem persists, please contact a "
                         "Purple dispatch manager."))
                  (filter :on_duty expired-couriers)))
      (sql/with-connection db-conn
        (sql/update-values
         "couriers"
         [(str "id IN (\""
               (s/join "\",\"" (map :id expired-couriers))
               "\")")]
         {:connected 0})))))

(defn remind-couriers
  "Notifies couriers if they have not Accepted an order that's assign to them."
  [db-conn]
  (let [assigned-orders (!select db-conn "orders" ["*"] {:status "assigned"})
        tardy? (fn [time-assigned]
                 (<= (+ time-assigned config/courier-reminder-time)
                     (quot (System/currentTimeMillis) 1000)
                     (+ time-assigned config/courier-reminder-time
                        (- (quot config/process-interval 1000) 1))))
        twilio-url (str config/base-url "twiml/courier-new-order")
        f #(when (tardy? (get-event-time (:event_log %) "assigned"))
             (call-user db-conn (:courier_id %) twilio-url))]
    (run! f assigned-orders)))

(defn new-assignments
  [os cs]
  (let [new-and-first #(and (:new_assignment %)
                            (= 1 (:courier_pos %)))]
    (filter
     (comp new-and-first val)
     (fmap (comp keywordize-keys (partial into {}))
           (compute-suggestion
            {"orders" (->> os
                           (map #(assoc %
                                        :status_times
                                        (-> (:event_log %)
                                            (s/split #"\||\s")
                                            (->> (remove s/blank?)
                                                 (apply hash-map)
                                                 (fmap read-string)))
                                        :zone (order->zone-id %)))
                           (map (juxt :id stringify-keys))
                           (into {}))
             "couriers" (->> cs
                             (map #(assoc % :zones (apply list (:zones %))))
                             (map (juxt :id stringify-keys))
                             (into {}))})))))

;; We start with a prev-state of blank; so that auto-assign is called when
;; server is booted.
(def prev-state (atom {:current-orders []
                       :on-duty-couriers []}))

;; If you added to the select-keys for 'cs' and include :last_ping
;; or :lat and :lng, then auto-assign would run every time courier changes
;; position; which may or may not be desirable.
(defn get-state
  [os cs]
  {:current-orders (map #(select-keys % [:id :status :courier_id]) os)
   :on-duty-couriers (map #(select-keys % [:id :active :on_duty :connected
                                           :busy :zones]) cs)})

(defn diff-state?
  "Has state changed significantly to trigger an auto-assign call?"
  [os cs]
  {:post [(reset! prev-state (get-state os cs))]}
  (not= @prev-state (get-state os cs)))

(defn auto-assign
  [db-conn]
  (catch-notify
   (let [os (get-all-current db-conn)
         cs (couriers/get-all-on-duty db-conn)]
     (!insert db-conn
              "state_log"
              {:data (str {:current-orders
                           (map #(select-keys % [:id :status :courier_id]) os)
                           :on-duty-couriers
                           (map #(select-keys % [:id :active :on_duty
                                                 :connected :busy :zones
                                                 :gallons_87 :gallons_91
                                                 :lat :lng :last_ping]) cs)})})
     (when (diff-state? os cs)
       (run! #(orders/assign db-conn (key %) (:courier_id (val %))
                             :no-reassigns true)
             (new-assignments os cs))))))
