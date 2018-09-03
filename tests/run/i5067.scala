object Test {
  def main(args: Array[String]): Unit = {
    null match {
      case Some(_) => println("matches Some")
      case (_, _) => println("matches Pair")
      case null => println("matches null literal")
    }
  }
}
