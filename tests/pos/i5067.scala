class Foo {
  val Some(_) = null
  val Some(_) = ???
  val (_, _) = null
  val (_, _, _) = ???
  ??? match {
    case Some(_) => ()
    case (_, _, _) => ()
  }
}
