# MapTheThings-Server Developer Info

## Running Locally

Create your .env file with the following settings:
```
AWS_ACCESS_KEY=[Your access key]
AWS_SECRET_KEY=[Your secret key]
TTN_APP_EUI=[Your TTN app EUI]
TTN_ACCESS_PASSWORD=[Your TTN access password]
GRID_CACHE_SIZE=4000
MESSAGES_BUCKET_NAME=[Your S3 bucket name for raw messages]
GRIDS_BUCKET_NAME=[Your S3 bucket name for grids]
MESSAGES_QUEUE_NAME=[Your SQS queue name]
```

```sh
$ env `cat .env` lein run
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
  appkey: [MapTheThings App Key] // Connects this API call to a particular user.
  msg_seq: [Sequence number for this message] // Used to link ping attempt with TTN message
  dev_eui: "00000000DEADBEEF" // Combined with msg_seq to create unique ID
  latitude: X.X
  longitude: Y.Y
  altitude: Z.Z
  timestamp: 2016-05-22T06:05:38.681605388Z
}
- GET /api/v0/grids/Lat1/Lon1/Lat2/Lon2 - Get list of URL's where grid data is available
{
  refs: [
    ["http://s3.amazonaws.com/nyc.thethings.map.grids/..."]  // Cover box with single grid - 1k cells
    ["", "", "", ""]                            // Cover box with 4 grids - 4k cells
    ["", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", ] // Cover box with 16 grids - 16k cells
  ]
}
- GET [S3 grid url]
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

## Geohashing
Use https://github.com/kungfoo/geohash-java to perform Geo hashing.
Internally we use a modified hash that captures the number of significant bits
followed by a hex formatting of hash. (geohash.org uses character encoding that
supports encoding only multiples of 5 significant bits.)

```
LevelCode = 0-63 significant bit count mapped to characters A-Za-z0-9_:
HexHash = Hash significant value formatted as hex string
Hash = [LevelCode][HexHash]
```

Level1 - 1 grid, A0, -180:180 Longitude, -90:90 Latitude
Level2 - 4 grids, C0, C1, C2, C3
LevelN - 4^N separate grids
Level20 - Smallest grid we use

Each grid record represents a 32x32 grid of cells contained within it.
Sample data is summarized within each cell. Loading a single grid supplies
a front end with up to 1024 cells to show.
In a level 20 grid, the cells are about ~1 foot "square".

Grid storage keys on S3 are of the form: ```[reverse(Hash)]-[Version]```.
We reverse the hash in order to enable S3 to partition the table more efficiently.

## Algorithm
- For each sample arriving in queue
  - Store in raw record
  - For each of 20 containing grids 0, 2, 4, 6, etc bits of hash
    - Load from cache or S3
    - Update value for cell containing sample
  - Remove sample from queue
- For each updated grid, write back to S3 after 30 seconds
- Use cache to avoid loading grids from S3

## Message flow
- Message arrives
- Parse and dispatch to receive channel
- Receive channel listeners include
-- S3 Logger - log received message
-- SQS Writer - write to SQS
