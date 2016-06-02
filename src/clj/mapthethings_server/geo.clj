(ns mapthethings-server.geo
  (:require [environ.core :refer [env]])
  (:import [com.amazonaws.auth BasicAWSCredentials]
           [com.amazonaws.services.dynamodbv2 AmazonDynamoDBClient]
           [com.amazonaws.services.dynamodbv2.model CreateTableRequest ProvisionedThroughput ResourceInUseException]
           [com.amazonaws.regions Regions]
           #_[com.amazonaws.geo GeoDataManagerConfiguration GeoDataManager]
           #_[com.amazonaws.geo.util GeoTableUtil]))

#_(defn get-ddb []
  (let [credentials (BasicAWSCredentials. (env :aws-access-key) (env :aws-secret-key))
        ddb (AmazonDynamoDBClient. credentials)
        ; region (Regions/getRegion Regions/US_WEST_2)
        ; _ (.setRegion ddb region)
       ]
    ddb))

#_(defn geo-config [ddb]
  (let [config (GeoDataManagerConfiguration. ddb, "geo-test")]
    #_(-> config
      (.withHashKeyAttributeName "customHashKey")
      (.withRangeKeyAttributeName "customId")
      (.withGeohashAttributeName "customGeohash")
      (.withGeoJsonAttributeName "customGeoJson")
      (.withGeohashIndexName "custom-geohash-index"))
    config))

#_(defn geo-manager [ddb]
  (GeoDataManager. (geo-config ddb)))

#_(defn create-table [ddb]
  (let [createTableRequest (GeoTableUtil/getCreateTableRequest (geo-config ddb))
        provisionedThroughput (-> (new ProvisionedThroughput)
                                (.withReadCapacityUnits 5)
                                (.withWriteCapacityUnits 5))]
    (.setProvisionedThroughput createTableRequest provisionedThroughput)
    (.createTable ddb createTableRequest)))
