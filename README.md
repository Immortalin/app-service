# Purple App Service

[![Build Status](https://travis-ci.com/Purple-Services/app-service.svg?token=qtYcDv5JYzqmyunRnB93&branch=dev)](https://travis-ci.com/Purple-Services/app-service)

The RESTful web service that the Purple mobile app uses

## Running Locally

### Getting started

There a few git repos that make up the Purple ecosystem. Create a "Purple-Services"
dir somewhere in your home dir or other appropriate place to keep all of the repos.

These repos make up the clojure server-side code:

Git repo: Purple-Services/app-service
Clojure project name: app
Description: mobile app api + logic specific to mobile app

Git repo: Purple-Services/dashboard-service
Clojure project name: dashboard
Description: dashboard api + logic specific to dashboard

Git repo: Purple-Services/common
CLojure project name: common
Description: library containing majority of logic (used by web/dashboard-service) + database calls + misc

Git repo: Purple-Services/opt
Clojure project name: opt
Description: library containing optimization logic (auto-assign heuristics, gas station planning, gas station list aggregation code).


Clone all of these repos to your Purple-Services dir. The common and opt libraries must be installed into your local repository.

```bash
~/Purple-Services/common$ lein install
~/Purple-Services/opt$ lein install
```

There should be little, if any, editing of these two libraries during development. However, sometimes common
must be developed in parallel to dashboard-service or app-service. Use the checkouts/ dir (https://github.com/technomancy/leiningen/blob/master/doc/TUTORIAL.md#checkout-dependencies) to facilitate this by linking to your local repos with ln.

```bash
~/Purple-Services/app-service/checkouts$ ln -s ~/Purple-Services/common common
~/Purple-Services/app-service/checkouts$ ln -s ~/Purple-Services/opt opt
```

You'll end up with a dir structure like this:

    .
    |-- project.clj
    |-- README.md
    |-- checkouts
    |   `-- common [link to ~/Purple-Services/common]
    |   `-- opt    [link to ~/Purple-Services/opt]
    `-- src
        `-- app
            `-- handler.clj



### Configuration

For local development, <project_root>/profiles.clj is used to define environment variables. However, profiles.clj isincluded in .gitignore and is not included in the repository. When you first start working on the project, you will have to create profiles.clj in the project root dir using the following template:

```clojure
{:dev { :env {:aws-access-key-id "ANYTHINGWHENLOCAL"
              :aws-secret-key "ANYTHINGWHENLOCAL"
              :db-host "localhost" ; AWS host: "purple-dev-db.cqxql2suz5ru.us-west-2.rds.amazonaws.com"
              :db-name "ebdb"
              :db-port "3306"
              :db-user "purplemaster"
              :db-password "localpurpledevelopment2015" ; AWS pwd: HHjdnb873HHjsnhhd
              :email-user "no-reply@purpledelivery.com"
              :email-password "HJdhj34HJd"
              :stripe-private-key "sk_test_6Nbxf0bpbBod335kK11SFGw3"
              :sns-app-arn-apns "arn:aws:sns:us-west-2:336714665684:app/APNS_SANDBOX/Purple" ;; sandbox is also used for couriers on prod
              :sns-app-arn-gcm  "arn:aws:sns:us-west-2:336714665684:app/GCM/Purple" ;; also used on prod
              :twilio-account-sid "AC0a0954acca9ba8c527f628a3bfaf1329"
              :twilio-auto-token "3da1b036da5fb7716a95008c318ff154"
              :twilio-form-number "+13239243338"
              :base-url "http://localhost:3000/"
              :has-ssl "NO"
              :segment-write-key "test"
              :sift-science-api-key "test"
              :dashboard-google-browser-api-key "AIzaSyA0p8k_hdb6m-xvAOosuYQnkDwjsn8NjFg"
              :env "dev"}
       :dependencies [[javax.servlet/servlet-api "2.5"]
                      [ring-mock "0.1.5"]]}}
```

Because profiles.clj will override the entires for :profiles in project.clj, the required :dependencies must be included in profiles.clj

**Note**: The value of :db-host is the database host used for development. If you have MySQL configured on your machine, you can use the value "localhost" with a :db-password that you set. Otherwise, you can use the AWS valueshost and pwd values to access the remote development server. You will eventually need to setup a local MySQL server in order to run tests that access the database. See "Using a local MySQL Database for Development" below about how to configure this.

### Get private dependencies

There are two dependencies that are private to Purple, common and opt. 
### Request addition of your IP address to RDS

Navigate to https://www.whatismyip.com/ and send your IP address to Chris in order to be added to the AWS RDS. You will have to update your IP address whenever it changes. This step must be completed in order to access the test database so that you will be able to develop locally. This must be done before continuing further if you plan to connect to the development database on AWS as opposed to using your local MySQL database.

### Start the local server for development


To start a web server for the application, run:

    lein ring server

from the app-service dir

### Open the Purple home page in a browser

After a succesful launch of the web server, your browser should automatically navigate to http://localhost:3000/ If not, navigate to http://localhost:3000/ and you will see the Purple home page. If you are unable to load the page, there is a problem with your local configuration.

### Confirm /login is working properly

Use the following curl command in a bash shell on Mac OS X to test the /login URL:
```bash
$ curl -X POST -H "Content-Type: application/json" -d '{ "type": "native", "platform_id": "elwssdell.scssshristasfadsfopher@gmail.com", "auth_key": "myPassworsasdd"}' localhost:3000/user/login
```

You should see a response similar to this:

```bash
{"success":true,"token":"pC0BhWGvXBjSqIUmzrzA5tWPXqpR5aSt0IK0NdmiLUAu3YuJUP6MQy6eh0gaL6M1acP6S9RNSg5tDO40dtADJX9KALJC5oL2kHPRzfL0yXq2DBwZ9nj9pYO9I9PjQItI","user":{"id":"LkD7ebDcAaq37CMKPhvD","type":"native","email":"elwssdell.scssshristasfadsfopher@gmail.com","name":"Test User","phone_number":"773-508-0888","referral_code":"V8VMM","referral_gallons":0,"is_courier":false,"has_push_notifications_set_up":false},"vehicles":[],"orders":[],"cards":[],"account_complete":true}
```

## Client

### Errors

You may encounter window.alert() errors which may only provide brief messages. This is due to the fact that the client has mobile-specific error alerts that work in the native iOS and Android app, but not in the Chrome browser. You will have to further investigate errors by navigating to the "Network" tab in Chrome Developer Tools menu and looking at the 'Response' section for the last request you made.


### Initial client setup
The client is a Sencha Touch web application written in Coffeescript. Clone Purple-Services/app into Purple-Services.

1. Compile coffeescript
```bash
coffee -o . -cb src
```
2. Edit app/util.js

Change "VERSION" to "LOCAL".
Edit "WEB_SERVICE_BASE_URL" for "LOCAL" to reflect the local dev environment. i.e. "http://localhost:3000/"

3. Open the client

```bash
Purple-Services/app$ /Applications/Google\ Chrome.app/Contents/MacOS/Google\ Chrome --disable-web-security --user-data-dir=/tmp/chrome2/ index_debug.html
```

Note: The server must be running in order for the client to run properly.

You will be presented with a map and a 'Request Gas' button. Chrome will request to use your location information. If you allow it, Chrome will move the map to reflect your current location information. In order to test the app, you will need to change your current location to an address served by Purple. To do this:

### Create an account

1. Allow location services
2. Click on the current address in the bottom purple bar.
3. You will be immediately sent to the login screen. Create a new account.
   (If you have problems, check in phpmyadmin that the username you choose isn't already taken)
4. You will now be shown a login page. Createa new account.
5. Click on the address in the lower purple bar.
6. Type "Beverly Hills"
7. Click on "Beverly Hills CA, United States"

### Request Gas


8. Click on the 'Request Gas' button. You will be taken to the 'Add Vehicle' menu.
9. Enter information for the 'Year','Make','Model','Color','Gas' and 'License Plate'. Click and Drag in order to scroll through popup menus. The 'Take Photo' menu item can be left blank. You won't be able to use this feature in Chrome because it requires a native mobile camera.
10. Click 'Save Changes'. If you have any errors, see the 'Errors' section above.
11. You will be taken to the 'Request Gas' menu. After making your selections, click 'Review Order'. Click 'Confirm Order' to confirm it.
12. You will have to add credit card information. You can use '4242424242424242' as the Card Number and any valid expiration and cvc number (i.e. three digit number) and zip code. Click 'Save Changes'.
13. After you review the order, 'Confirm Order'. Dismiss the alert. You will be presented with a list of your Orders.

### Login with the Courier Client

Couriers fulfill orders by delivering gas to the client. A courier should have the app logged in and running in the background on their phone whenever they are on duty. The courier app will ping the web service every 10 seconds. When a new order is created, all connected couriers get notified. Unassigned orders are shown in the courier app in a list. They can press the 'Accept Order' button for that order.

To test out how the courier works, first logout of the Customer Client application.

1. Click the "Hamburger" icon in the top left of the client. Select 'Account' in the side-menu.
2. Click the 'Logout' button on the bottom center.
3. Login with the test courier credentials

**email**: testcourier1@test.com

**password**: qwerty123


### Fulfilling an order

After you login as a courier, you will be presented with the 'Orders' page. Test fulfilling the order you just placed.

Note: The courier client will ping the server every ten seconds. The server must have the proper lat lng coordinates. If you do not allow for location tracking when using the browser, lat lng will be null and the server ping will fail.

1. Click on an open order. It will have a dark purple bar on the left.
2. You will not be able to click 'Accept Order'. Instead you will have to go to the console and type
```javascript
	util.ctl('Orders').nextStatus()
```
3. You will be taken back to orders. Notice that the right hand status bar has started to fill for this order.
4. In order to 'Start Route' type 'util.ctl('Orders').nextStatus()' into the console again.
5. Continue this process of opening the order and using 'util.ctl('Orders').nextStatus()' to go through the order status. You must be on the Order's page in order to cycle through the courier process.


The statuses in the Dashboard cycle through as Unassigned -> Assigned -> Accepted -> Enroute -> Servicing -> Complete or Cancelled. Currently we skip Assigned and go straight to Accepted because the courier can choose which ones they want.

### Using a local MySQL Database for Development

The development test server for the MySQL database is used in the stub given above. We have provided SQL files in order to setup a local database for development. This is a preferred method of development, due to the fact that there can be problems with connection pools being occupied when multiple users are developing on the AWS MySQL server. Also, some tests rely on fixtures that use a local database call ebdb_test. Without configuring a local MySQL server, tests which use this fixture will fail.

There are three files provided in the database dir:

**ebdb_setup.sql** will drop and create the ebdb and ebdb_test database locally.

**ebdb.sql** will create the tables in the ebdb and ebdb_test database and populate them with test data.

**ebdb_zcta.sql.gz** will create a table of Zip Code Tabulation Areas (zcta) which define the borders of a zip code

ebdb.sql can be updated with the retrieve_db script

```bash
~/Purple-Services/app-service$ scripts/retrieve_db ebdb.sql
```

The scripts for populating the database rely on mysqldump to retrieve the
ebdb database from the server. However, fields that are of type text,
medium_text or other blob types do not retain their default values.

The following values must be altered:

orders.text_rating, orders.event_log, coupons.zip_codes, users.stripe_cards

The following command can be used to change the default value to empty ("")

```sql
> ALTER TABLE `orders` CHANGE `text_rating` `text_rating` TEXT CHARACTER SET utf8 COLLATE utf8_general_ci NOT NULL DEFAULT "";
```

In order to use it, you obviously must have MySQL working on your local machine. It is advisable to also use phpmyadmin.

We have also provided a clojure script that must be run from the command line using the '[lein-exec](https://github.com/kumarshantanu/lein-exec)' plugin. In order to use it, add the following line to your {:user {:plugins }} entry of your ~/.lein/profiles.clj:

{:user {:plugins [[lein-exec "0.3.5"]]}}

You must provide the script with the root password of your MySQL server in order to create the permissions for 'purplemaster' needed by the Purple server application.

Due to the size of ebdb_zcta.sql.gz (34MB), it takes about 2 minutes for the following script to complete on a 2.3GHz Intel Core i7 with 8GB ram and SSD disk:

```bash
web-service $ lein exec -p scripts/setupdb.clj root_password="your_secret_password"
Creating ebdb database and granting permissions to purplemaster
Creatings tables and populating them in ebdb as user purplemaster
(0 0 0 0 0 0 0 1 0 65 0 3 0 43 0 256 226 0 63 0 4 3 6 1 2 1 5 1 7 1 9 7 4 2 6 3 5 1 1 5 7 1 3 1 1 7 1 3 1 1 1 3 4 3 3 4 0 1 1 65 3 43 482 63 118 1 1 482 1 0 0 0)
web-service $
```

**Note:** The password used for puplemaster must be the same across the following files:
```
src/profiles.clj
database/ebdb_setup.sql
```

## Deploying to Development Server

The server is manually configured with the required System properties in the AWS console. Therefore, the top entry of src/purple/config.clj only sets vars when the environment is "test" or "dev".

Use lein-beanstalk to deploy to AWS ElasticBeanstalk (you must first set up your ~/.lein/profiles.clj with AWS creds):

    lein beanstalk deploy development

## Conventions & Style

Generally, try to follow: https://github.com/bbatsov/clojure-style-guide

Try to keep lines less than 80 columns wide.

## License

Copyright © 2016 Purple Services Inc
