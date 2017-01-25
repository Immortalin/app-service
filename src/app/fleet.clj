(ns app.fleet
  (:require [common.config :as config]
            [common.db :refer [!select !insert !update mysql-escape-str]]
            [common.util :refer [rand-str-alpha-num coerce-double
                                 compute-total-price]]
            [common.zones :refer [get-zip-def]]
            [common.vin :refer [get-info-batch]]
            [clojure.string :as s]))

(defn get-fleet-location-by-id
  [db-conn fleet-location-id]
  (first (!select db-conn "fleet_locations" ["*"] {:id fleet-location-id})))

(defn get-fleet-locations
  [db-conn courier-id lat lng]
  (let [locations (!select db-conn "fleet_locations" ["*"] {})]
    {:success true
     :accounts locations
     :default_account_id (:id (first locations))}))

(defn get-by-index
  "Get an el from coll whose k is equal to v. Returns nil if el doesn't exist"
  [coll k v]
  (first (filter #(= (k %) v) coll)))

(defn add-delivery
  "Report a delivery of fuel for a fleet account."
  [db-conn fleet-location-id courier-id vin license-plate
   gallons gas-type is-top-tier & {:keys [vin-infos]}]
  (cond
    (and (s/blank? vin) (s/blank? license-plate))
    {:sucess false :message "You must enter a VIN or a License Plate."}
    
    (zero? gallons)
    {:sucess false :message "You must enter the number of gallons delivered."}
    
    :else
    (let [gas-price (or (some-> (get-fleet-location-by-id db-conn
                                                          fleet-location-id)
                                :address_zip
                                (#(get-zip-def db-conn %))
                                :gas-price
                                (get gas-type))
                        0)
          service-fee 0
          vin-infos (or vin-infos
                        (when vin
                          (->> [(s/upper-case vin)]
                               distinct
                               (into [])
                               get-info-batch
                               :resp)))
          vin-info (when vin-infos
                     (get-by-index vin-infos :vin (s/upper-case vin)))]
      (!insert db-conn
               "fleet_deliveries"
               {:id (rand-str-alpha-num 20)
                :courier_id courier-id
                :fleet_location_id fleet-location-id
                :vin (s/upper-case vin)
                :year (:year vin-info)
                :make (:make vin-info)
                :model (:model vin-info)
                :license_plate (s/upper-case license-plate)
                :gallons gallons
                :gas_type gas-type
                :is_top_tier (or is-top-tier false)
                :gas_price gas-price
                :service_fee service-fee
                :total_price (compute-total-price gas-price
                                                  gallons
                                                  service-fee)}))))

(defn add-deliveries
  [db-conn
   courier-id ; i.e., the auth'd user-id
   deliveries]
  (let [vin-infos (->> (map :vin deliveries)
                       distinct
                       (into [])
                       get-info-batch
                       :resp)]
    (run! #(add-delivery db-conn
                         (:account_id %)
                         courier-id
                         (:vin %)
                         (:license_plate %)
                         (if (s/blank? (:gallons %))
                           0
                           (coerce-double (:gallons %)))
                         (:gas_type %)
                         (:is_top_tier %)
                         :vin-infos vin-infos)
          deliveries)))
