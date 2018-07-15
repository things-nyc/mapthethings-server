var latScale = 93206.0;
var lonScale = 46603.0;
var hdopScale = 1000.0;

function signExtend(v, len) {
  if (len<4 && ((1<<(len*8-1))&v)) {
    // Sign extend v
    v = v | (1<<31)>>(8*(4-len)-1);
  }
  return v;
}

function getIntLSBOrder(bytes, offset, len) {
  var v = 0;
  for (var i = 0; i<len; ++i) {
    v = v | (bytes[offset+i] << (8*i));
  }
  return signExtend(v, len);
}

function getIntNetworkOrder(bytes, offset, len) {
  var v = 0;
  for (var i = offset; i<(offset+len); ++i) {
    v = v << 8 | bytes[i];
  }
  return signExtend(v, len);
}

function parseFormat12(bytes) {
  return {
    latitude: getIntLSBOrder(bytes, 1, 3) / latScale,
    longitude: getIntLSBOrder(bytes, 4, 3) / lonScale,
  };
}

function parseFormat5(bytes) {
  return {
    latitude: getIntNetworkOrder(bytes, 1, 3) / latScale,
    longitude: getIntNetworkOrder(bytes, 4, 3) / lonScale,
    altitude: getIntNetworkOrder(bytes, 7, 2),
    hdop: getIntNetworkOrder(bytes, 9, 2) / hdopScale,
  };
}

function Decoder(bytes, port) {
  var decoded = null;

  switch (bytes[0]) {
    case 1:
      decoded = parseFormat12(bytes);
      break;
    case 2:
      decoded = parseFormat12(bytes);
      break;
    case 5:
      decoded = parseFormat5(bytes);
      break;
  }

  return decoded;
}
