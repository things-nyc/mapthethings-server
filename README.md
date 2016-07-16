
# MapTheThings-Server

The server portion of [MapTheThings](http://map.thethings.nyc), the
global coverage map for The Things Network (TTN).

## Server Responsibilities
- Handle messages from MapTheThings devices (nodes) routed via TTN.
- Handle messages from mobile apps indicating failed message attempts.
- Import coverage data submitted by the global user base.
- Generate summary of GIS data at multiple scales to support map display
- Serve single page MapTheThings Web app that shows coverage
- Serve data to MapTheThings Web app - different resolution depending on zoom

## Contributing Data

1. MapTheThings - App & Node (Pending)
  Use the Map The Things [iOS app](http://github.com/things-nyc/mapthethings-ios)
  and [hardware node](http://github.com/things-nyc/mapthethings-node)
  to submit coverage samples.

2. Custom Nodes
  You are welcome to create your own TTN nodes and use them to
  transmit GPS coordinates to the Map The Things server.</p>
   - AppSKey: ```430D53B272A647AF5DFF6A167AB79A20```
   - NwkSKey: ```804243642C1E3B04366D36C3909FCAA2```
   - Data: Send text strings of the form
      ```{"lat":40.7128,"lng":-74.0059}``` or just ```40.7128,-74.0059```

3. Upload Data
  If you want to upload existing map data, submit a pull request with your
  data in a reasonable format (CSV, JSON, XML, YAML, EDN) in the
  [project/data](http://github.com/things-nyc/mapthethings/data)
  directory. Make certain to include GPS coordinates, RSSI and SNR values
  for successful TTN messages. Samples missing RSSI or SNR indicate failed
  transmissions.

## Contributing Code
This is a current work in progress as of Summer 2016. We welcome pull requests.

### TODO
- Support "lat,lng" TTN messages
- Support partitioning grid update work
- Record histogram of number of gateways receiving a message: {1 40 2 3 5 1}
- Use mock S3 and SQS for testing
- Collect distance to gateway stats (for gateways that report their position)
- Ensure that once a grid has dropped out of the cache, the version loaded from S3 is the latest.
- Support requesting lower depth within Geobox - client can then do higher resolution pass if desired
- Support uploading CSV of existing data
- Write task that sends 1000's of messages to test API.
- Login with Firebase Authentication (or something else enabling Twitter, Facebook, Github, Google)
- Deliver APP key for each user to use - may be revoked
- BUG - Markers (like European samples) disappear at zoom levels 0, 1, and 2. Pan and they sometimes show.
- BUG - "clojure.lang.PersistentHashMap cannot be cast to clojure.lang.IPersistentStack" doing big import. Memory related?
- Include Gateways from https://thethingsnetwork.org/api/v0/gateways/
- Load TTNmapper data: ttnmapper.org/dumps
- Share our data in an S3 bucket
- Include histogram of spreading factor in summary data
- Accept binary data packets. Fixed point reals: lat,lon,alt,hdop
- Take latitude, longitude, altitude, and HDOP (horizontal dilution of precision)
- Plot shape of points (d3.delauney filterd by alpha)
- Make map take entire viewport with text in overlays

### DONE
- DONE - Subscribe to TTN MQQT broker at staging.thethingsnetwork.org:1883
- DONE - Update global hierarchy of grids
- DONE - API serves list of URL's covering Geobox
- DONE - Web app renders rectangles in JSON: Loads from public S3 and plots lat/lon boxes
- DONE - Support TTN message with plain text for testing (it already worked)
- DONE - Accept ping messages that indicate an attempt to write from a location.
- DONE - Post messages to SQS first thing
- DONE - Store raw messages to S3 (we'll want to replay them sometime)
- DONE - Import JSON array of samples
- DONE - BUG - Zooming too quickly leads to undeleted rectangles
- DONE - Drive grid updates from SQS
- DONE - Import data from http://ttn-utrecht.ltcm.net/
- DONE - Record stats as :rssi {:cnt x :q y :avg z} rather than {:rssi-cnt x :rssi-q y :rssi-avg z}
- DONE - Restart MQTT connection periodically. Saw "org.eclipse.paho.client.mqttv3.internal.ClientState checkForActivity
   SEVERE: frank.1464903570129: Timed out as no activity, keepAlive=60,000 lastOutboundActivity=1,464,905,984,787 lastInboundActivity=1,464,905,130,918". Suffered from connection having been dropped when testing node.
   Rely on :keep-alive-interval to trigger failure, but then actually reconnect and re-subscribe.

## Hosting
The server is hosted on Heroku and uses Amazon S3 and SQS for storage and queuing.

## License
Source code for Map The Things is released under the MIT License,
which can be found in the [LICENSE](LICENSE) file.
