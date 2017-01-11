# MapTheThings-Server Developer Info

## Running Locally

Create your .env file with the following settings:
```
AWS_ACCESS_KEY=[AWS identity access key]
AWS_SECRET_KEY=[AWS identity secret key]
TTN_APP_EUI=[TTN app EUI]
TTN_ACCESS_PASSWORD=[TTN access password]
GRID_CACHE_SIZE=4000
MESSAGES_BUCKET_NAME=[S3 bucket name for raw messages]
GRIDS_BUCKET_NAME=[S3 bucket name for grids]
MESSAGES_QUEUE_NAME=[SQS queue name]
```

```sh
$ env `cat .env` lein run
```

```sh
$ heroku local web
```

## AWS Configuration
### S3 Grids bucket
- Create bucket.
- Add permission for Everyone to List the bucket
- Add permission for your AWS identity to write to the bucket, either by
  specific permission or policy on the bucket, or in the IAM console by
  enabling the identity full access to S3.
- Add a policy to the bucket that enables anonymous access to its contents
  ```
  {
  	"Version": "2012-10-17",
  	"Statement": [
  		{
  			"Sid": "AddPerm",
  			"Effect": "Allow",
  			"Principal": "*",
  			"Action": "s3:GetObject",
  			"Resource": "arn:aws:s3:::[BUCKET NAME]/*"
  		}
  	]
  }
  ```
- Add CORS like the following to enable the web app to load cross site from S3
  ```xml
  <?xml version="1.0" encoding="UTF-8"?>
  <CORSConfiguration xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
      <CORSRule>
          <AllowedOrigin>http://[APP HOSTNAME]</AllowedOrigin>
          <AllowedMethod>GET</AllowedMethod>
          <MaxAgeSeconds>3000</MaxAgeSeconds>
          <AllowedHeader>*</AllowedHeader>
      </CORSRule>
      <CORSRule>
          <AllowedOrigin>http://localhost:5000</AllowedOrigin>
          <AllowedMethod>GET</AllowedMethod>
          <MaxAgeSeconds>3000</MaxAgeSeconds>
          <AllowedHeader>*</AllowedHeader>
      </CORSRule>
  </CORSConfiguration>
  ```

### S3 Messages bucket
- Create bucket.
- Add permission for your AWS identity to write to the bucket, either by
  specific permission or policy on the bucket, or in the IAM console by
  enabling the identity full access to S3.
- Since this is for private use by the app, there's no need for the extra
  Policy or CORS configuration.

### SQS Queues
- Create an SQS queue
- Optionally, reduce the maximum message size
  to 20k or lower, because messages should be small (< 1k) and lowering the
  limit will cause a noticable failure should a large message come through.
  A large message might indicate that someone is abusing the API.
- Optionally, set the receive message wait time to something longer than 0
  seconds in order to have the opportunity to reduce slightly the number of
  requests to SQS.
- Create a similarly configured Dead Letter queue and configure the first queue
  to send failed messages to it.

## API
- MQTT message
Bytes representing UTF-8 encoded string that contains either JSON
  ```
  {
  ```
- PUT /api/v0/transmissions - Write that an attempt was made
  ```
  {
    appkey: [MapTheThings App Key] // Connects this API call to a particular user.
    msg_seq: [Sequence number for this message] // Used to link attempt with TTN message
    dev_eui: "00000000DEADBEEF" // Combined with msg_seq to create unique ID
    latitude: X.X
    longitude: Y.Y
    altitude: Z.Z
    timestamp: 2016-05-22T06:05:38.681605388Z
  }
  ```
- GET /api/v0/grids/Lat1/Lon1/Lat2/Lon2 - Get list of URL's where grid data is available
  ```
  {
    refs: [
      ["http://s3.amazonaws.com/nyc.thethings.map.grids/..."]  // Cover box with single grid - 1k cells
      ["", "", "", ""]                            // Cover box with 4 grids - 4k cells
      ["", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", ] // Cover box with 16 grids - 16k cells
    ]
  }
  ```
- GET [S3 grid url]
  ```
  {
    cells: {
      cell-hash: {
        clat: X.X, clon: X.X, // Coordinates of center of cell
        lat1: X.X, lon1: X.X, // Coordinates of upper left corner of cell
        lat2: X.X, lon2: X.X, // Coordinates of lower right corner of cell

        rssi: {cnt, avg, q, std} // Cummulative cal per https://en.wikipedia.org/wiki/Standard_deviation#Rapid_calculation_methods
        lsnr: {cnt, avg, q, std}

        // Sample volume
        count: N,       // Count of all received/reported completed TTN messages

        // Packet loss measurement
        attempt-cnt: N, // Count of known transmission attempts
        success-cnt: N, // Count of known transmissions successfully completed

        // Data source summary
        ttn-cnt: N,     // Count of messages receive by server via TTN network (includes success-cnt)
        import-cnt: N,  // Count of samples imported
        api-cnt: N,     // Count of samples reported to API
      },
    }
  }
  ```

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
Server listens via TTN subscription (MQTT) and HTTP server.
- Message arrives via TTN or HTTP POST or import logic
- Parse and validate - on error return failure to submitter, if possible.
- Post EDN formatted message to SQS work queue containing
  ```{msg: {parsed}, :raw {input}}```

Separately, SQS worker reads batches of 10 messages and processes them.
- For each sample arriving in queue
  - Store incoming sample in parsed and raw form into S3 "messages" bucket.
    Useful for replaying entire data set when we figure out we did summary wrong.
  - For each of 20 containing grids 0, 2, 4, 6, etc bits of hash
    - Load from cache or S3
    - Update value for cell containing sample
  - Remove sample from queue
- For each updated grid, write back to S3 after 30 seconds

## Performance
Import of 5,700 Utrecht samples was the first bigger test.
Import of 5,700 records took about 4min. Processing 3,000 took 10 min. 5,700 took 19 min.
Both processes running simultaneously on MacBook Pro 2.8 GHz i7 and 16GB RAM.
