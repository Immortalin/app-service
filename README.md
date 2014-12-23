# Purple Web Service

The RESTful web service that the Purple mobile app and other clients (e.g., Admin dashboard) use.

## Prerequisites

You will need [Leiningen][1] 1.7.0 or above installed.

[1]: https://github.com/technomancy/leiningen

## Running Locally

To start a web server for the application, run:

    lein ring server

## Deploying to Development Server (on AWS ElasticBeanstalk)

Use lein-beanstalk to deploy to AWS ElasticBeanstalk (you must first set up your ~/.lein/profiles.clj with AWS creds):

    lein beanstalk deploy development

## License

Copyright © 2014 Purple Services Inc
