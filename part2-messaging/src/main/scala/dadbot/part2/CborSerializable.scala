package dadbot.part2

/** Messages that cross the network. `application.conf` binds this trait to Pekko's Jackson CBOR serializer. */
trait CborSerializable
