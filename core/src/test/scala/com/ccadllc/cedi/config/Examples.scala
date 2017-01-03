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

import com.typesafe.config.ConfigFactory

object Examples extends App {

  sealed trait Connector { val port: Int }
  case class HttpConnector(port: Int) extends Connector
  case class HttpsConnector(port: Int, clientAuthType: Option[ClientAuthType]) extends Connector

  sealed trait ClientAuthType
  object ClientAuthType {
    case object Want extends ClientAuthType
    case object Need extends ClientAuthType

    def fromString(s: String): Option[ClientAuthType] =
      s.toLowerCase match {
        case "want" => Some(Want)
        case "need" => Some(Need)
        case _ => None
      }
  }

  val connectorsParser: ConfigParser[List[Connector]] = {
    val connectorParser: ConfigParser[Connector] = {
      ConfigParser.string("type").bind {
        case "http" =>
          ConfigParser.int("port").map { p => HttpConnector(p) }
        case "https" =>
          val clientAuthTypeParser: ConfigParser[ClientAuthType] =
            ConfigParser.fromString("client-auth")(ClientAuthType.fromString(_).toRight("Must be either 'want' or 'need'"))
          (ConfigParser.int("port") ~ clientAuthTypeParser.optional).map { case (p, cat) => HttpsConnector(p, cat) }
      }
    }
    ConfigParser.list("connectors")(connectorParser)
  }

  val result = connectorsParser.parse(ConfigFactory.parseString(
    """|connectors: [
       |  {
       |    type: http
       |    port: 80
       |  },
       |  {
       |    type: https
       |    port: 443
       |    client-auth: want
       |  }
       |]""".stripMargin
  ))

  println(result)
}
