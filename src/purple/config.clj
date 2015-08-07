(ns purple.config)

;; stub for local testing DEV DB
(System/setProperty "AWS_ACCESS_KEY_ID" "AKIAJLB35GOFQUJZCX5A")
(System/setProperty "AWS_SECRET_KEY" "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")
(System/setProperty "DB_HOST" "aaey4vi1u5i4jq.cqxql2suz5ru.us-west-2.rds.amazonaws.com")
(System/setProperty "DB_NAME" "ebdb")
(System/setProperty "DB_PORT" "3306")
(System/setProperty "DB_USER" "purplemaster")
(System/setProperty "DB_PASSWORD" "HHjdnb873HHjsnhhd")
(System/setProperty "EMAIL_USER" "no-reply@purpledelivery.com")
(System/setProperty "EMAIL_PASSWORD" "HJdhj34HJd")
(System/setProperty "STRIPE_PRIVATE_KEY" "sk_test_6Nbxf0bpbBod335kK11SFGw3")
(System/setProperty "SNS_APP_ARN_APNS" "arn:aws:sns:us-west-2:336714665684:app/APNS_SANDBOX/Purple")
(System/setProperty "SNS_APP_ARN_GCM" "arn:aws:sns:us-west-2:336714665684:app/GCM/Purple")
(System/setProperty "TWILIO_ACCOUNT_SID" "AC0a0954acca9ba8c527f628a3bfaf1329")
(System/setProperty "TWILIO_AUTH_TOKEN" "3da1b036da5fb7716a95008c318ff154")
(System/setProperty "TWILIO_FROM_NUMBER" "+13239243338")
(System/setProperty "BASE_URL" "http://localhost:3000/")
(System/setProperty "BASIC_AUTH_USERNAME" "purpleadmin")
(System/setProperty "BASIC_AUTH_PASSWORD" "gasdelivery8791")


;;;; Base Url of the web service
;; Should include trailing forward-slash (e.g., "http://domain.com/")
(def base-url (System/getProperty "BASE_URL"))

;;;; Database
(def db-host (System/getProperty "DB_HOST"))
(def db-port (System/getProperty "DB_PORT"))
(def db-name (System/getProperty "DB_NAME"))
(def db-user (System/getProperty "DB_USER"))
(def db-password (System/getProperty "DB_PASSWORD"))
(def db-config
  {:classname "com.mysql.jdbc.Driver"
   :subprotocol "mysql"
   :subname (str "//" db-host ":" db-port "/" db-name)
   :user db-user
   :password db-password})

;;;; Basic Auth, for Dashboard
;; ...with edit privileges
(def basic-auth-admin
  {:username (System/getProperty "BASIC_AUTH_USERNAME")
   :password (System/getProperty "BASIC_AUTH_PASSWORD")})
;; ...with read-only privileges (the page is /stats instead of /dashboard)
(def basic-auth-read-only
  {:username (System/getProperty "BASIC_AUTH_READ_ONLY_USERNAME")
   :password (System/getProperty "BASIC_AUTH_READ_ONLY_PASSWORD")})

;;;; Payment
(def stripe-api-url "https://api.stripe.com/v1/")
(def stripe-private-key (System/getProperty "STRIPE_PRIVATE_KEY"))
(def default-currency "usd")

;;;; Dispatch 
;; How often to process the zone order queues (i.e., dispatch/zq) (millis)
;; MUST BE multiple of 1000 (because of dispatch/remind-courier)
(def process-interval (* 1000 5))
;; How long of courier app not responding that we consider them to be
;; disconnected. (seconds)
(def max-courier-abandon-time (* 60 2))
;; How many long after a new order has been accepted to a courier but they
;; have not begun the route; to then send them a reminder (seconds)
(def courier-reminder-time (* 60 5))

;;;; Email
(def email-from-address (System/getProperty "EMAIL_USER"))
(def email {:host "smtp.gmail.com"
            :user (System/getProperty "EMAIL_USER")
            :pass (System/getProperty "EMAIL_PASSWORD")
            :ssl :yes!!!11})

;;;; Push Notifications (using AWS SNS)
;; the customer apns arn is either Sandbox or Live APNS
(def sns-app-arn-apns (System/getProperty "SNS_APP_ARN_APNS"))
;; the courier arn is always Sandbox
(def sns-app-arn-apns-courier "arn:aws:sns:us-west-2:336714665684:app/APNS_SANDBOX/Purple")
(def sns-app-arn-gcm (System/getProperty "SNS_APP_ARN_GCM"))

;;;; SMS and Phone Calls (using Twilio)
(def twilio-account-sid (System/getProperty "TWILIO_ACCOUNT_SID"))
(def twilio-auth-token (System/getProperty "TWILIO_AUTH_TOKEN"))
(def twilio-from-number (System/getProperty "TWILIO_FROM_NUMBER"))

;;;; Service Hours (time bracket to allow orders to be placed)
;; hour of day, start and end (in PST/PDT), both are inclusive
;; e.g., [8 19] service available from 8:00:00am to 7:59:59pm
;; the way things are coded, you can't wrap around past midnight
;; ALSO CHANGE ERROR MESSAGE on Line 96 of dispatch.clj
(def service-time-bracket [7 20])

;;;; Delivery time guarantee options
;; Key is number of minutes till deadline
;; ALSO CHANGE in dispatch.clj where the hardcoded service fee is being used
;; for old versions of the app
(def delivery-times {180 {:service_fee 0
                          :text "within 3 hours (free)"
                          :order 0}
                     60  {:service_fee 100
                          :text "within 1 hour ($1)"
                          :order 1}})

;;;; Referral Program
;; Discount value in cents of using a referral code
(def referral-referred-value -1000) ;; should be negative!
;; The # of gallons credited to the Referrer upon usage of their coupon code
(def referral-referrer-gallons 5)

;; The flow of order status; nil means status can't be changed
(def status->next-status
  {"unassigned"  "assigned"
   "assigned"    "accepted"
   "accepted"    "enroute"
   "enroute"     "servicing"
   "servicing"   "complete"
   "complete"    nil
   "cancelled"   nil})
;; An order can be cancelled only if its status is one of these
(def cancellable-statuses ["unassigned" "assigned" "accepted" "enroute"])

;; These will be initiated on startup with values from db.
;; No need to modify them directly here.
(def gas-price-87 (atom 0))
(def gas-price-91 (atom 0))
