object Test extends dotty.runtime.LegacyApp {
   val bar: Null = null

   def foo(x: Array[Int]|Null) = x
   def baz(x: String|Null) = x

   // first line was failing
   println(foo(bar))
   // this line worked but good to have a double check
   println(baz(bar))
}
