
# MapTheThings-Server

- Handle message from MapTheThings devices (nodes) routed via TTN.
- Serve single page MapTheThings Web app that shows coverage
- Serve data to MapTheThings Web app - different resolution depending on zoom
- Generate summary of GIS data at multiple scales to support API

## TODO
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
- Ensure that once a grid has dropped out of the cache, the version loaded from S3 is the latest.
- Support partitioning grid update work
- Support requesting lower depth within Geobox - client can then do higher resolution pass if desired
- Write task that sends 1000's of messages
- Support uploading CSV of existing data
- Login with Firebase Authentication (or something else enabling Twitter, Facebook, Github, Google)
- Deliver APP key for each user to use - may be revoked

## Running Locally

```sh
$ env `cat .env` lein repl
user=> (require 'thingsburg-server.web)
user=>(def server (thingsburg-server.web/-main))
```

```sh
$ heroku local web
```

## API
- MQTT message
{
  "payload":"{ // b64 encoded
    "msgid": "[UNIQUE_MSG_ID]",
    "appkey": "[THINGSBURG_APP_KEY]",
    "longitude":25.0,
    "latitude":25.0
  }",
  "port":1,
  "counter":4,
  "dev_eui":"00000000DEADBEEF",
  "metadata":[
    {
      "frequency":865.4516,
      "datarate":"SF9BW125",
      "codingrate":"4/8",
      "gateway_timestamp":1,
      "gateway_time":"2016-05-22T06:05:38.645444008Z",
      "channel":0,
      "server_time":"2016-05-22T06:05:38.681605388Z",
      "rssi":-5,
      "lsnr":5.3,
      "rfchain":0,
      "crc":0,
      "modulation":"LoRa",
      "gateway_eui":"0102030405060708",
      "altitude":0,
      "longitude":0,
      "latitude":0
    }
  ]
}
- PUT /api/v0/pings - Write that an attempt was made
{
  appkey: [Thingsburg App Key] // Connects this API call to a particular user.
  msgid: [Unique ID for this message] // Used to link ping attempt with TTN message
  latitude: X.X
  longitude: Y.Y
  altitude: Z.Z
  timestamp: T
}
- GET /api/v0/grids/Lat1/Lon1/Lat2/Lon2 - Get list of URL's where grid data is available
{
  refs: [
    ["http://s3.amazonaws.com/Thingsburg/..."]  // Cover box with single grid - 1k cells
    ["", "", "", ""]                            // Cover box with 4 grids - 4k cells
    ["", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", ] // Cover box with 16 grids - 16k cells
  ]
}
- GET [Cell url]
{
  cells: [
    {
      center: hash,
      width: Xmax, height: Ymax,
      lat1: X.X, lon1: X.X,
      lat2: X.X, lon2: X.X,
      x: x-index, y: y-index, // Position of this cell in the grid
      varq: Q, // Running intermediate variable. std-dev = sqrt(Q / N)
      rssi-avg: X, // Running calc as A https://en.wikipedia.org/wiki/Standard_deviation#Rapid_calculation_methods
      rssi-std: X,
      pings: N,
      ok: N,
    },
  ],
  lookup: { // Give X Y index in grid, what index in cells array above. -1 if none.
    "X,Y": cells-index,
  }
}

## S3 Geohashing
A world quadrants
Each level is 32x32 grid, 1024 max samples
Level0 - -180:180 Longitude, -90:90 Latitude - A single grid
Level1 - -180:0/-90:0, 0:180/-90:0, -180:0/0:90, 0:180/0:90 - World in 4 grids
LevelN - 4^N separate grids
Level20 - Grid with 32x32 cells, each of which is ~1 foot square
Level25 - ~1 foot squares covering Earth

Keys are of the form: /Grids/[ReverseHash]/Level

Keys are of the form: /Raw/[hash]-[MsgID]

### Algorithm
- For each sample arriving in queue
-- Store in raw record
-- For each of 20 containing grids 0, 2, 4, 6, etc bits of hash
--- Load from cache or S3
--- Update value for cell containing sample
-- Remove sample from queue
- For each updated grid
-- If after no further updates in 30 second, write grid
-- If oldest unsaved update is 60 seconds old, write grid

## Message flow
- Message arrives
- Parse and dispatch to receive channel
- Receive channel listeners include
-- Logger - log received message
-- SQS Writer - write to SQS
