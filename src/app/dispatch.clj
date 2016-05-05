(ns app.dispatch
  (:require [common.db :refer [conn !select !insert !update]]
            [common.config :as config]
            [common.util :refer [! cents->dollars-str five-digit-zip-code
                                 get-event-time in? minute-of-day->hmma
                                 only-prod segment-client send-sms
                                 split-on-comma unix->minute-of-day]]
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

(def job-pool (at-at/mk-pool))

(defn get-gas-prices
  "Given a zip-code, return the gas prices."
  [zip-code]
  (if (zip-in-zones? zip-code)
    {:success true
     :gas_prices (get-fuel-prices zip-code)}
    {:success true
     :gas_prices (get-fuel-prices "90210")}))

(defn delivery-times-map
  "Given a service fee, create the delivery-times map."
  [service-fees]
  (let [fee-str #(if (= % 0) "free" (str "$" (cents->dollars-str %)))]
    {180 {:service_fee (:180 service-fees)
          :text (str "within 3 hours (" (fee-str (:180 service-fees)) ")")
          :order 0}
     60 {:service_fee (:60 service-fees)
         :text (str "within 1 hour (" (fee-str (:60 service-fees)) ")")
         :order 1}}))

;; TODO this function should consider if a zone is actually "active"
(defn available
  [open-minute close-minute zip-code octane]
  (let [service-fees (get-service-fees zip-code)
        delivery-times (delivery-times-map service-fees)
        good-times (filter #(<= open-minute
                                (unix->minute-of-day
                                 (quot (System/currentTimeMillis) 1000))
                                close-minute)
                           (keys delivery-times))]
    {:octane octane
     :gallon_choices config/gallon_choices
     :gallons 15 ;; keep this for legacy app version < 1.2.2
     :price_per_gallon ((keyword octane) (get-fuel-prices zip-code))
     :times (into {} (map (juxt identity delivery-times) good-times))}))

(defn availability
  "Get an availability map to tell client what orders it can offer to user."
  [db-conn zip-code user-id]
  (let [user (users/get-user-by-id db-conn user-id)]
    (segment/track segment-client user-id "Availability Check"
                   {:address_zip (five-digit-zip-code zip-code)})
    (merge
     {:success true
      :user (assoc (select-keys user [:referral_gallons :referral_code])
                   :subscription (subscriptions/get-usage db-conn user))}
     ;; construct a map of availability
     (if (and (zip-in-zones? zip-code) (:active (get-zone-by-zip-code zip-code)))
       ;; we service this ZIP code
       (let [[open-minute close-minute] (get-service-time-bracket zip-code)]
         {:availabilities
          (map (partial available open-minute close-minute zip-code) ["87" "91"])
          :unavailable-reason ;; iff unavailable (client determines from :availabilities)
          (cond (= 5 open-minute close-minute) ;; special case for closing zone
                (str "We are busy. There are no couriers available. Please try "
                     "again later.")

                (= 6 open-minute close-minute)
                (str "We are closed for the holiday. We will be back soon. Please "
                     "enjoy your holiday!")

                (= 7 open-minute close-minute)
                (str "We want everyone to stay safe and are closed due to inclement "
                     "weather. We will be back shortly!")
                
                :else (str "Sorry, the service hours for this ZIP code are "
                           (minute-of-day->hmma open-minute)
                           " to "
                           (minute-of-day->hmma close-minute)
                           " every day."))})
       ;; we don't service this ZIP code
       {:availabilities [{:octane "87"
                          :gallons 15
                          :times {} ;; no times available
                          :price_per_gallon 0}
                         {:octane "91"
                          :gallons 15
                          :times {} ;; no times available
                          :price_per_gallon 0}]
        :unavailable-reason (str "Sorry, we are unable to deliver gas to your "
                                 "location. We are rapidly expanding our service "
                                 "area and hope to offer service to your "
                                 "location very soon.")}))))

(! (def process-db-conn (conn))) ;; ok to use same conn forever? have to test..

(defn update-courier-state
  "Marks couriers as disconnected as needed."
  [db-conn]
  (let [expired-couriers (->> (couriers/get-all-expired db-conn)
                              (users/include-user-data db-conn))]
    (when-not (empty? expired-couriers)
      (only-prod (run!
                  #(send-sms
                    (:phone_number %)
                    "You have just disconnected from the Purple Courier App.")
                  expired-couriers))
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
  (let [os (get-all-current db-conn)
        cs (couriers/get-all-on-duty db-conn)]
    (!insert
     db-conn
     "state_log"
     {:data
      (str {:current-orders (map #(select-keys % [:id :status :courier_id]) os)
            :on-duty-couriers (map #(select-keys % [:id :active :on_duty
                                                    :connected :busy :zones
                                                    :gallons_87 :gallons_91
                                                    :lat :lng :last_ping]) cs)})
      })
    (when (diff-state? os cs)
      (run! #(orders/assign db-conn (key %) (:courier_id (val %))
                            :no-reassigns true)
            (new-assignments os cs)))))

(defn process
  "Does a few periodic tasks."
  []
  ((juxt update-courier-state remind-couriers auto-assign) process-db-conn))

(! (def process-job (at-at/every config/process-interval process job-pool)))
