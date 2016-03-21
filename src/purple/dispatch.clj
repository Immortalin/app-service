(ns purple.dispatch
  (:use purple.util
        clojure.data.priority-map
        [purple.db :only [conn !select !insert !update mysql-escape-str]]
        [clojure.algo.generic.functor :only [fmap]]
        clojure.walk)
  (:require [purple.config :as config]
            [purple.orders :as orders]
            [purple.users :as users]
            [ardoq.analytics-clj :as segment]
            [clojure.java.jdbc :as sql]
            [overtone.at-at :as at-at]
            [clojure.string :as s]
            [purple.couriers :as couriers])
  (:import [org.joda.time DateTime DateTimeZone]
           [purpleOpt PurpleOpt]))

(def job-pool (at-at/mk-pool))

(defn get-all-zones-from-db
  "Get all zones from the database."
  [db-conn]
  (!select db-conn "zones" ["*"] {}))

;; holds all zone definitions in local memory, some parsing in there too
(! (def zones (atom (map #(update-in % [:zip_codes] split-on-comma)
                         (get-all-zones-from-db (conn))))))

(defn update-zones!
  "Update the zones var held in memory with that in the database"
  [db-conn]
  (reset! zones (map #(update-in % [:zip_codes] split-on-comma)
                     (get-all-zones-from-db db-conn))))

(defn get-zones-by-ids
  "Given a string of comma-seperated zones, return all zones in string."
  [zones-str]
  (let [zone-matches-id?
        (fn [zone]
          (some
           identity
           (map #(= (:id zone) %)
                (map read-string (split-on-comma zones-str)))))]
    (filter zone-matches-id? @zones)))

(defn order->zone-id
  "Determine which zone the order is in; gives the zone id."
  [order]
  (let [zip-code (five-digit-zip-code (:address_zip order))]
    (:id (first (filter #(in? (:zip_codes %) zip-code)
                        @zones)))))

(defn zip-in-zones?
  "Determine whether or not zip-code can be found in zones."
  [zip-code]
  (->> @zones
       (filter #(in? (:zip_codes %) (five-digit-zip-code zip-code)))
       seq
       boolean))

(defn get-zone-by-zip-code
  "Given a zip code, return the corresponding zone."
  [zip-code]
  (-> (filter #(= (:id %) (order->zone-id {:address_zip zip-code})) @zones)
      first))

(defn get-fuel-prices
  "Given a zip code, return the fuel prices for that zone."
  [zip-code]
  (-> zip-code
      (get-zone-by-zip-code)
      :fuel_prices
      (read-string)))

(defn get-service-fees
  "Given a zip-code, return the service fees for that zone."
  [zip-code]
  (-> zip-code
      (get-zone-by-zip-code)
      :service_fees
      (read-string)))

(defn get-service-time-bracket
  "Given a zip-code, return the service time bracket for that zone."
  [zip-code]
  (-> zip-code
      (get-zone-by-zip-code)
      :service_time_bracket
      (read-string)))

;; This is only considering the time element. They could be disallowed
;; for other reasons.
(defn get-one-hour-orders-allowed
  "Given a zip-code, return the time in minutes that one hour orders are
  allowed."
  [zip-code]
  (-> zip-code
      (get-service-time-bracket)
      first
      (+ 0) ;; for 1 1/2 hour delay: (+ 90)
      ))

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
  (let [fee #(if (= % 0) "free" (str "$" (cents->dollars-str %)))]
    {180 {:service_fee (:180 service-fees)
          :text (str "within 3 hours (" (fee (:180 service-fees)) ")")
          :order 0}
     60 {:service_fee (:60 service-fees)
         :text (str "within 1 hour (" (fee (:60 service-fees)) ")")
         :order 1}}))

;; TODO this function should consider if a zone is actually "active"
(defn available
  [good-time?-fn zip-code octane]
  (let [service-fees (get-service-fees zip-code)
        delivery-times (delivery-times-map service-fees)
        good-times (filter #(and (zip-in-zones? zip-code) (good-time?-fn %))
                           (keys delivery-times))]
    {:octane octane
     :gallon_choices config/gallon_choices
     :gallons 15 ;; keep this for legacy app version < 1.2.2
     :price_per_gallon ((keyword octane) (get-fuel-prices zip-code))
     :times (into {} (map (juxt identity delivery-times) good-times))}))

(defn availability
  "Get courier availability for given constraints."
  [db-conn zip-code user-id]
  (let [user (users/get-user-by-id db-conn user-id)]
    (segment/track segment-client user-id "Availability Check"
                   {:address_zip (five-digit-zip-code zip-code)})
    (if (and (zip-in-zones? zip-code)
             (:active (get-zone-by-zip-code zip-code)))
      ;; good ZIP, but let's check if good time
      (let [opening-minute (first (get-service-time-bracket zip-code))
            closing-minute (last  (get-service-time-bracket zip-code))
            current-minute (unix->minute-of-day (quot (System/currentTimeMillis)
                                                      1000))
            during-holiday? (< 1451700047 ;; 6pm Jan 1st
                               (quot (System/currentTimeMillis) 1000)
                               1451725247) ;; Jan 2nd
            good-time?-fn (fn [minutes-needed]
                            (and (not during-holiday?)
                                 (<= opening-minute
                                     current-minute
                                     ;;(- closing-minute minutes-needed)
                                     ;; removed the check for enough time
                                     ;; because our end time just means we accept orders
                                     ;; until then (regardless of deadline)
                                     closing-minute)))]
        {:success true
         :availabilities (map (partial available good-time?-fn zip-code)
                              ["87" "91"])
         ;; if unavailable (as the client will determine from :availabilities)
         :unavailable-reason
         (if during-holiday?
           "Sorry, we're closed for the holiday. We'll be back January 2nd!"
           (str "Sorry, the service hours for this ZIP code are "
                (minute-of-day->hmma opening-minute)
                " to "
                (minute-of-day->hmma closing-minute)
                " every day."))
         :user (select-keys user [:referral_gallons :referral_code])
         ;; LEGACY: we're still sending this for old versions of the app
         :availability [{:octane "87"
                         :gallons (if (zip-in-zones? zip-code)
                                    15 0) ;; just assume 15 gallons
                         :time [1 3]
                         :price_per_gallon (:87 (get-fuel-prices zip-code))
                         :service_fee [100 0]}
                        {:octane "91"
                         :gallons (if (zip-in-zones? zip-code)
                                    15 0)
                         :time [1 3]
                         :price_per_gallon (:91 (get-fuel-prices zip-code))
                         :service_fee [100 0]}]})
      ;; bad ZIP, we don't service there yet
      {:success true
       :user (select-keys user [:referral_gallons :referral_code])
       :availabilities [{:octane "87"
                         :gallons 15
                         :times {}
                         :price_per_gallon 0}
                        {:octane "91"
                         :gallons 15
                         :times {}
                         :price_per_gallon 0}]
       :availability [{:octane "87"
                       :gallons 0
                       :time [1 3]
                       :price_per_gallon 0
                       :service_fee [100 0]}
                      {:octane "91"
                       :gallons 0
                       :time [1 3]
                       :price_per_gallon 0
                       :service_fee [100 0]}]
       :unavailable-reason (str "Sorry, we are unable to deliver gas to your "
                                "location. We are rapidly expanding our service "
                                "area and hope to offer service to your "
                                "location very soon.")})))

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
             (users/call-user db-conn (:courier_id %) twilio-url))]
    (run! f assigned-orders)))

(defn new-assignments
  [os cs]
  (let [new-and-first #(and (:new_assignment %)
                            (= 1 (:courier_pos %)))]
    (filter
     (comp new-and-first val)
     (fmap (comp keywordize-keys (partial into {}))
           (into {}
                 (PurpleOpt/computeSuggestion
                  (map->java-hash-map
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
                                    (into {}))})))))))

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
  (let [os (orders/get-all-current db-conn)
        cs (couriers/get-all-on-duty db-conn)]
    (!insert
     db-conn
     "state_log"
     {:data
      (str {:current-orders (map #(select-keys % [:id :status :courier_id]) os)
            :on-duty-couriers (map #(select-keys % [:id :active :on_duty
                                                    :connected :busy :zones
                                                    :gallons_87 :gallons_91
                                                    :lat :lng :last_ping]) cs)})})
    (when (diff-state? os cs)
      (run! #(orders/assign db-conn (key %) (:courier_id (val %))
                            :no-reassigns true)
            (new-assignments os cs)))))

(defn process
  "Does a few periodic tasks."
  []
  ((juxt update-courier-state remind-couriers auto-assign) process-db-conn))

(! (def process-job (at-at/every config/process-interval process job-pool)))

(defn courier-ping
  "The courier app periodically pings us with courier status details."
  [db-conn user-id lat lng gallons]
  (!update db-conn
           "couriers"
           {:lat lat
            :lng lng
            :gallons_87 (Double. (:87 gallons))
            :gallons_91 (Double. (:91 gallons))
            :connected 1
            :last_ping (quot (System/currentTimeMillis) 1000)}
           {:id user-id}))

(defn update-zone!
  "Update fuel_prices, service_fees and service_time_bracket for the zone with
  id.

  fuel-prices is an edn string map of the format
  '{:87 <integer cents> :91 <integer cents>}'.

  service-fees is an edn string map of the format
  '{:60 <integer cents> :180 <integer cents>}'.

  service-time-bracket is an edn string vector of the format
  '[<service-start> <service-end>]' where <service-start> and <service-end>
  are integer values of the total minutes elapsed in a day at a particular
  time.

  ex:
  The vector [450 1350] represents the time bracket 7:30am-10:30pm where
  7:30am is represented as 450 which is (+ (* 60 7) 30)
  10:30pm is represened as 1350 which is (+ (* 60 22) 30)"
  [db-conn id fuel-prices service-fees service-time-bracket]
  (!update db-conn "zones"
           {:fuel_prices fuel-prices
            :service_fees service-fees
            :service_time_bracket service-time-bracket}
           {:id id})
  ;; update the zones as well
  (update-zones! db-conn))

(defn courier-assigned-zones
  "Given a courier-id, return a set of all zones they are assigned to"
  [db-conn courier-id]
  (let [zones (:zones (first
                       (!select db-conn
                                "couriers"
                                [:zones]
                                {:id courier-id})))]
    (if (nil? (seq zones))
      (set zones) ; the empty set
      (set
       (map read-string
            (split-on-comma zones))))))

(defn get-courier-zips
  "Given a courier-id, get all of the zip-codes that a courier is assigned to"
  [db-conn courier-id]
  (let [courier-zones (filter #(contains?
                                (courier-assigned-zones db-conn courier-id)
                                (:id %))
                              @zones)
        zip-codes (apply concat (map :zip_codes courier-zones))]
    (set zip-codes)))

(defn get-zctas-for-zips
  "Given a string of comma-seperated zips and db-conn, return a list of
  zone/coordinates maps."
  [db-conn zips]
  (let [in-clause (str "("
                       (s/join ","
                               (map #(str "'" % "'")
                                    (split-on-comma zips)))
                       ")")]
    (!select db-conn "zctas" ["*"] {}
             :custom-where (str "zip in " in-clause))))
