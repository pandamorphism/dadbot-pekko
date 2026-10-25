package dadbot.common

/** The conversation from Effect's Program.ts. */
object Script:
  val engineering = "#engineering"
  val random = "#random"

  val sebastian: ChatMessage = ChatMessage("Sebastian", "Why is this test flaky?")
  val mattia: ChatMessage = ChatMessage("Mattia", "It's flaky for me too")
  val tim: ChatMessage = ChatMessage("Tim", "Is CI down?")
