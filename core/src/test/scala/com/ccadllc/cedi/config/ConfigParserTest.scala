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

import scala.concurrent.duration._

import com.typesafe.config.{ Config, ConfigFactory, ConfigOriginFactory }
import org.scalatest.{ Matchers, WordSpec }

case class AddressAndPort(address: String, port: Int)
object AddressAndPort {
  val Pattern = """^(.+):(\d+)$""".r
  def fromString(s: String): Either[String, AddressAndPort] = {
    try {
      val Pattern(addr, portStr) = s
      Right(AddressAndPort(addr, portStr.toInt))
    } catch {
      case e: MatchError => Left("invalid syntax: must be address:port")
    }
  }
}

class ConfigParserTest extends WordSpec with Matchers {

  "the safe config extensions" should {
    val testOrigin = Some(ConfigOriginFactory.newSimple("hardcoded value"))

    case class Wibble(name: String, count: Int)
    val wibbleParser = (ConfigParser.string("name") ~ ConfigParser.int("count")).map(Wibble.tupled)

    "support ints" in {
      ConfigParser.int("foo").parse(ConfigFactory.parseString("foo: 42")) shouldBe Right(42)
      ConfigParser.int("foo").parse(ConfigFactory.parseString("foo: asdf")) shouldBe
        Left(ConfigErrors.of(ConfigError.WrongType(ConfigKey.Relative("foo"), "expected int", Some(ConfigOriginFactory.newSimple("String").withLineNumber(1)))))
    }

    "support int lists" in {
      ConfigParser.intList("foo").parse(ConfigFactory.parseString("foo: [23, 42]")) shouldBe Right(List(23, 42))
      ConfigParser.intList("foo").parse(ConfigFactory.parseString("foo: asdf")) shouldBe
        Left(ConfigErrors.of(ConfigError.WrongType(ConfigKey.Relative("foo"), "expected int list", Some(ConfigOriginFactory.newSimple("String").withLineNumber(1)))))
    }

    "support composition of parsers of scalar values" in {
      wibbleParser.parse(config("name" -> "foo", "count" -> "4")) shouldBe Right(Wibble("foo", 4))
      wibbleParser.parse(config()) shouldBe Left(ConfigErrors.of(
        ConfigError.Missing(ConfigKey.Relative("name")),
        ConfigError.Missing(ConfigKey.Relative("count"))
      ))
    }

    "support optional keys" in {
      wibbleParser.optional.parse(config("name" -> "foo", "count" -> "4")) shouldBe Right(Some(Wibble("foo", 4)))
      wibbleParser.optional.parse(config()) shouldBe Right(None)
    }

    "support string keys which are converted to an arbitrary type via a conversion function" in {
      ConfigParser.fromString("server")(AddressAndPort.fromString).parse(config("server" -> "127.0.0.1:5555")) shouldBe
        Right(AddressAndPort("127.0.0.1", 5555))
      ConfigParser.fromString("server")(AddressAndPort.fromString).parse(config("server" -> "127.0.0.1")) shouldBe
        Left(ConfigErrors.of(
          ConfigError.BadValue(ConfigKey.Relative("server"), "invalid syntax: must be address:port", testOrigin)
        ))
    }

    "support string lists" in {
      val itoa = (s: String) => try Right(s.toInt) catch { case e: NumberFormatException => Left(s"invalid integer: $s") }
      ConfigParser.fromStringList[Int]("counts")(itoa).parse(ConfigFactory.parseString("counts: [1, 2, 3, 4, 5]")) shouldBe
        Right(List(1, 2, 3, 4, 5))

      ConfigParser.fromStringList[Int]("counts")(itoa).parse(ConfigFactory.parseString("counts: [1, 2, asdf, 4, fdsa]")) shouldBe
        Left(ConfigErrors.of(
          ConfigError.BadValue(ConfigKey.Relative("counts[2]"), "invalid integer: asdf", Some(ConfigOriginFactory.newSimple("String").withLineNumber(1))),
          ConfigError.BadValue(ConfigKey.Relative("counts[4]"), "invalid integer: fdsa", Some(ConfigOriginFactory.newSimple("String").withLineNumber(1)))
        ))
    }

    "provides a humanized summary of config errors" in {
      val errors = ConfigErrors.of(
        ConfigError.WrongType(ConfigKey.Relative("connection.server"), "invalid syntax: must be address:port", testOrigin),
        ConfigError.Missing(ConfigKey.Relative("connection.path")),
        ConfigError.WrongType(ConfigKey.Relative("id"), "expected int value", testOrigin)
      )

      errors.description shouldBe (
        """|Failed to parse configuration.
           |  Wrong type for key connection.server - invalid syntax: must be address:port (hardcoded value).
           |  No value for required key connection.path.
           |  Wrong type for key id - expected int value (hardcoded value).""".stripMargin
      )
    }

    "support sub-configurations" in {
      val nestedWibbleParser = wibbleParser.under("wibble")
      nestedWibbleParser.parse(config("wibble.name" -> "foo", "wibble.count" -> "4")) shouldBe Right(Wibble("foo", 4))
      nestedWibbleParser.parse(config()) shouldBe Left(ConfigErrors.of(
        ConfigError.Missing(ConfigKey.Relative("wibble.name")),
        ConfigError.Missing(ConfigKey.Relative("wibble.count"))
      ))

      val strictNestedWibbleParser = wibbleParser.under("wibble", failFast = true)
      strictNestedWibbleParser.parse(config("wibble.name" -> "foo", "wibble.count" -> "4")) shouldBe Right(Wibble("foo", 4))
      strictNestedWibbleParser.parse(config()) shouldBe Left(ConfigErrors.of(
        ConfigError.Missing(ConfigKey.Relative("wibble"))
      ))
    }

    "report type mismatch when a subconfig parser parses a scalar value" in {
      ConfigParser.string("name").under("wibble").parse(config("wibble" -> "1")) shouldBe
        Left(ConfigErrors.of(ConfigError.WrongType(ConfigKey.Relative("wibble"), "expected an object but got a value", testOrigin)))
    }

    "report origin information in errors" in {
      val result = wibbleParser.under("wibble").parse(ConfigFactory.load())
      result shouldBe 'left
      val err = result.fold(_.head, _ => sys.error("should have been a failure"))
      err.origin.map(_.description).getOrElse("") should include("application.conf: 3")
    }

    "support marking keys in error messages non-relative" in {
      val p = ConfigParser.ask.bind { root =>
        ConfigParser.subconfig("foo", failFast = false) {
          ConfigParser.int("bar") ~ ConfigParser.anonymous { _ => ConfigParser.int("baz").withAbsoluteErrorKeys.parse(root) }
        }
      }
      p.parse(config()) shouldBe Left(ConfigErrors.of(
        ConfigError.Missing(ConfigKey.Relative("foo.bar")),
        ConfigError.Missing(ConfigKey.Absolute("baz"))
      ))
    }

    "support derivation" which {

      "derives config parsers from case classes" in {
        val derivedWibbleParser = ConfigParser.derived[Wibble]
        derivedWibbleParser.parse(config("name" -> "foo", "count" -> "4")) shouldBe Right(Wibble("foo", 4))
      }

      "adjusts case of config keys" in {
        case class Foo(titleCaseName: Int, myURL: String)
        ConfigParser.derived[Foo].parse(config("title-case-name" -> "42", "my-url" -> "http://localhost/foo")) shouldBe Right(Foo(42, "http://localhost/foo"))
      }

      "supports nested derivation" in {
        implicit val addressAndPortFieldParser: ConfigParser.DerivedConfigFieldParser[AddressAndPort] =
          ConfigParser.DerivedConfigFieldParser(ConfigParser.fromString(_)(AddressAndPort.fromString))

        case class ServerSettings(server: AddressAndPort, path: String, timeout: Option[FiniteDuration])
        case class AppSettings(connections: List[ServerSettings], id: Int)

        val appSettingsParser: ConfigParser[AppSettings] = ConfigParser.derived[AppSettings]

        appSettingsParser.parse(ConfigFactory.parseString(
          """|connections: [
             |  {
             |    server: "localhost:5555"
             |    path: "/foo"
             |    timeout: 30s
             |  }
             |]
             |id: 42
             |""".stripMargin
        )) shouldBe
          Right(AppSettings(List(ServerSettings(AddressAndPort("localhost", 5555), "/foo", Some(30.seconds))), 42))

        appSettingsParser.parse(ConfigFactory.parseString(
          """|connections: [
             |  {
             |    server: "localhost:5555"
             |    path: "/foo"
             |  }
             |]
             |id: 42
             |""".stripMargin
        )) shouldBe
          Right(AppSettings(List(ServerSettings(AddressAndPort("localhost", 5555), "/foo", None)), 42))

        appSettingsParser.parse(ConfigFactory.parseString(
          """|connections: [ { server: asdf } ]
             |id: asdf
             |""".stripMargin
        )) shouldBe
          Left(ConfigErrors.of(
            ConfigError.BadValue(ConfigKey.Relative("connections[0].server"), "invalid syntax: must be address:port", Some(ConfigOriginFactory.newSimple("String").withLineNumber(1))),
            ConfigError.Missing(ConfigKey.Relative("connections[0].path")),
            ConfigError.WrongType(ConfigKey.Relative("id"), "expected int", Some(ConfigOriginFactory.newSimple("String").withLineNumber(2)))
          ))
      }

      "supports full derivation of nested case classes" in {
        case class Wobble(string: String)
        case class Both(wibbles: Vector[Wibble], wobbles: Option[Wobble])

        val bothParser = ConfigParser.derived[Both]

        bothParser.parse(
          ConfigFactory.parseString(
            """wibbles: [
              |  {
              |    count= 7
              |    name = "hello"
              |  },
              |  {
              |    count= 6
              |    name = world
              |  }
              |]
              |wobbles.string = "goodbye"
            """.stripMargin
          )
        ) shouldBe Right(Both(Vector(Wibble("hello", 7), Wibble("world", 6)), Some(Wobble("goodbye"))))
      }
    }
  }

  private def config(ps: (String, String)*): Config = {
    import scala.collection.JavaConverters._
    ConfigFactory.parseMap(Map(ps: _*).asJava)
  }
}
