/*
 * Copyright 2016 Combined Conditional Access Development, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.ccadllc.cedi.config

import shapeless.{ ::, HNil }

object CompilationTest {
  import ConfigParser._

  val a: ConfigParser[(String, Int)] = string("foo") ~ int("bar")
  val b: ConfigParser[(String, Int, Boolean)] = a ~ bool("baz")
  val c: ConfigParser[(String, Int, Boolean, Int)] = b ~ int("qux")

  val d: ConfigParser[String :: Int :: Boolean :: HNil] = string("foo") :: int("bar") :: bool("baz")

  case class Qux(foo: String, bar: Int, baz: Boolean)
  val e: ConfigParser[Qux] = d.as[Qux]
  val f: ConfigParser[Qux] = subconfig("a.b.c") { derived[Qux] }
}
