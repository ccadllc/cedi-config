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

object Examples {

  import scala.concurrent.duration.FiniteDuration
  import com.typesafe.config.ConfigFactory

  case class CacheSettings(maxEntries: Int, ttl: Option[FiniteDuration])

  val parserDerived: ConfigParser[CacheSettings] = ConfigParser.derived[CacheSettings]

  val parsedDerived: Either[ConfigErrors, CacheSettings] = parserDerived.parse(ConfigFactory.parseString(
    """|max-entries: 1000
       |ttl: 10 seconds
       |""".stripMargin
  ))

  println(parsedDerived)

  val parserManual: ConfigParser[CacheSettings] =
    (ConfigParser.int("max-entries") ~ ConfigParser.duration("ttl").optional).map {
      case (maxEntries, ttl) =>
        CacheSettings(maxEntries, ttl)
    }

}
