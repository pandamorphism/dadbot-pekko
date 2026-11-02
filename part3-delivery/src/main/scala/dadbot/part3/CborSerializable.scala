package dadbot.part3

/** Messages, events and state that cross the network or go to the database. `application.conf` binds this trait to
  * Pekko's Jackson CBOR serializer.
  */
trait CborSerializable
