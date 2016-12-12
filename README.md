# Cedi Config

Quick links:

- [About the library](#about)
- [How to get latest version](#getit)
- [API Docs](https://oss.sonatype.org/service/local/repositories/releases/archive/com/ccadllc/cedi/config_2.12/1.0.0/config_2.12-1.0.0-javadoc.jar/!/com/ccadllc/cedi/config/index.html)

### <a id="about"></a>About the library

Cedi Config is a purely functional, scodec-style combinator library for converting [Typesafe `Config`](https://github.com/typesafehub/config)
objects to application specific types. The differentiating features of this library are:

 - errors in the configuration are returned as values -- exceptions are never thrown
 - all errors present in the configuration are reported, not just the first error that is encountered
 - configuration parsers can be built manually or derived automatically from the structure of application specific types
 - limited dependencies -- only Typesafe Config and Shapeless

Example of usage:

```scala
import scala.concurrent.duration.FiniteDuration
// import scala.concurrent.duration.FiniteDuration

import com.ccadllc.cedi.config.{ ConfigParser, ConfigErrors }
// import com.ccadllc.cedi.config.{ConfigParser, ConfigErrors}

import com.typesafe.config.ConfigFactory
// import com.typesafe.config.ConfigFactory

// First, let's create an application specific settings class
case class CacheSettings(maxEntries: Int, ttl: Option[FiniteDuration], log: Boolean)
// defined class CacheSettings

// Let's derive a parser from the settings class. The generated parser looks for a
// config value for each field of the CacheSettings class.
val derivedParser: ConfigParser[CacheSettings] = ConfigParser.derived[CacheSettings]
// derivedParser: com.ccadllc.cedi.config.ConfigParser[CacheSettings] = (int("max-entries") :: (duration("ttl").optional :: (boolean("log") :: HNil))).as[CacheSettings]

// Let's create a new parser by placing the derived parser in the "com.myapp"
// config namespace.
val appParser: ConfigParser[CacheSettings] = derivedParser.under("com.myapp")
// appParser: com.ccadllc.cedi.config.ConfigParser[CacheSettings] = subconfig("com.myapp", false)((int("max-entries") :: (duration("ttl").optional :: (boolean("log") :: HNil))).as[CacheSettings])

// Sweet parsing!
val settings: Either[ConfigErrors, CacheSettings] =
  appParser.parse(ConfigFactory.parseString(
    """|com.myapp {
       |  max-entries: 1000
       |  ttl: 10 seconds
       |  log: false
       |}""".stripMargin))
// settings: Either[com.ccadllc.cedi.config.ConfigErrors,CacheSettings] = Right(CacheSettings(1000,Some(10000000000 nanoseconds),false))

// Sweet error reporting!
val empty: Either[ConfigErrors, CacheSettings] =
  appParser.parse(ConfigFactory.parseString(""))
// empty: Either[com.ccadllc.cedi.config.ConfigErrors,CacheSettings] = Left(ConfigErrors(Missing(com.myapp.max-entries), Missing(com.myapp.log)))
```

Here, we used the `ConfigParser.derived` method to generate a parser for the
application specific `CacheSettings` class. We then used the `under` method
to place the derived parser under the `com.myapp` config key.

The `ConfigParser` type provides the main API of this library. A `ConfigParser[A]`
converts a Typesafe `Config` object to an `Either[ConfigErrors, A]` via the `parse`
method. In this example, we created a `ConfigParser[CacheSettings]` by calling
the `derived` method on the `ConfigParser` companion, which did some compile time
reflection on the structure of the case class to generate the parser. It returned
a parser that parses a value for each field of the case class based on the field
name -- e.g., the `maxEntries` field was parsed via `config.getInt("max-entries")`.

The `parse` method returns an `Either[ConfigErrors, A]` with a `Right(a)` if the
value was parsed successfully and a `Left(errs)` if parsing failed due to one or
more errors. The `ConfigErrors` type provides a non-empty list of errors that
occurred when parsing, where each error is a subtype of `ConfigError`. The
`ConfigErrors` type also provides a nicely formatted message via the `description`
method:

```scala
val errMsg: Option[String] =
  appParser.parse(ConfigFactory.parseString("")).left.toOption.map(_.description)
// errMsg: Option[String] =
// Some(Failed to parse configuration.
//   No value for required key com.myapp.max-entries.
//   No value for required key com.myapp.log.)
```

#### Manually creating parsers

In the last example, we derived a `ConfigParser` for the `CacheSettings` class using
`ConfigParser.derived`. Instead, we could have manually created a parser using the
constructors in the `ConfigParser` companion object and the combinators on the `ConfigParser`
class.

Let's start by creating a parser for each value type in the `CacheSettings` case class.
We can do this by using constructors on the `ConfigParser` companion, each of which takes
the key to read from config as an argument.

```scala
val maxEntries: ConfigParser[Int] = ConfigParser.int("max-entries")
// maxEntries: com.ccadllc.cedi.config.ConfigParser[Int] = int("max-entries")

val ttl: ConfigParser[FiniteDuration] = ConfigParser.duration("ttl")
// ttl: com.ccadllc.cedi.config.ConfigParser[scala.concurrent.duration.FiniteDuration] = duration("ttl")

val log: ConfigParser[Boolean] = ConfigParser.bool("log")
// log: com.ccadllc.cedi.config.ConfigParser[Boolean] = boolean("log")
```

Now we need to make the `ttl` parser succeed with a `None` if the key doesn't exist
in the configuration when parsing. We can do this with the `optional` combinator:

```scala
val ottl: ConfigParser[Option[FiniteDuration]] = ttl.optional
// ottl: com.ccadllc.cedi.config.ConfigParser[Option[scala.concurrent.duration.FiniteDuration]] = duration("ttl").optional
```

Finally, we need to put the individual parsers together in to a parser for the case
class.

One way to do this is by first creating a tuple parser using the `~` combinator and
then mapping over the result to convert the tuple to a `CacheSettings`:

```scala
val triple: ConfigParser[(Int, Option[FiniteDuration], Boolean)] =
  maxEntries ~ ottl ~ log
// triple: com.ccadllc.cedi.config.ConfigParser[(Int, Option[scala.concurrent.duration.FiniteDuration], Boolean)] = ((int("max-entries") ~ duration("ttl").optional) ~ boolean("log"))

val viaTupleMap: ConfigParser[CacheSettings] =
  triple.map { case (maxEntries, ttl, log) => CacheSettings(maxEntries, ttl, log) }
// viaTupleMap: com.ccadllc.cedi.config.ConfigParser[CacheSettings] = ((int("max-entries") ~ duration("ttl").optional) ~ boolean("log")).map(<function>)
```

Alternatively, we can use `::` instead of `~` along with the `as[CacheSettings]` combinator
to build a parser of the generic representation of `CacheSettings` and then convert that generic
representation to the case class via `as`:

```scala
val viaAs: ConfigParser[CacheSettings] =
  (maxEntries :: ottl :: log).as[CacheSettings]
// viaAs: com.ccadllc.cedi.config.ConfigParser[CacheSettings] = (int("max-entries") :: (duration("ttl").optional :: boolean("log"))).as[CacheSettings]
```

There are a lot more constructors and combinators -- take a look at the ScalaDoc for `ConfigParser` to see them.

#### Deriving parsers

Often it is more convenient to derive parsers using `ConfigParser.derived`, like in the original example.
The `derived` constructor works by doing compile-time reflection on the structure of the case class, looking
at each component. For each component, a number of things happen:
 - an implicit `DerivedConfigFieldParser[X]` is resolved, where `X` is the type of the component
 - a config key is calculated by taking the component name and converting it from camelCase to lowercase with dashes
 - the computed config key is passed to the `apply` method on the resolved `DerivedConfigFieldParser`, yielding
   a `ConfigParser[X]` for the specified key
After that's occurred for every component, all the generated parsers are combined with something that approximates
`(c0 :: c1 :: ... :: cN).as[CaseClass]`.

There are implicit `DerivedConfigFieldParser` instances for the supported scalar types (e.g., `Int`, `String`, `FiniteDuration`),
optional types, and `List`/`Vector`. Additional instances can be provided implicitly. For example:

```scala
case class AddressAndPort(address: String, port: Int)

implicit val addressAndPortFieldParser: ConfigParser.DerivedConfigFieldParser[AddressAndPort] =
  ConfigParser.DerivedConfigFieldParser { key =>
    ConfigParser.fromString(key) { value =>
      value.split(":").toList match {
        case addr :: portStr :: Nil =>
          val port =
            try Right(portStr.toInt)
            catch { case _: NumberFormatException => Left("port must be a number")}
          port.right.flatMap { p =>
            if (p >= 0) Right(new AddressAndPort(addr, p))
            else Left("port must be >= 0")
          }
        case other =>
          Left("must be address:port")
      }
    }
  }

case class ServerSettings(endpoint: AddressAndPort)
```

```scala
val serverSettingsParser = ConfigParser.derived[ServerSettings]
// serverSettingsParser: com.ccadllc.cedi.config.ConfigParser[ServerSettings] = (fromConfigValue(endpoint) :: HNil).as[ServerSettings]

val successful = serverSettingsParser.parse(ConfigFactory.parseString("""endpoint: "google.com:80" """))
// successful: Either[com.ccadllc.cedi.config.ConfigErrors,ServerSettings] = Right(ServerSettings(AddressAndPort(google.com,80)))

val failed = serverSettingsParser.parse(ConfigFactory.parseString("""endpoint: "google.com" """)).left.toOption.get.description
// failed: String =
// Failed to parse configuration.
//   Bad value for key endpoint - must be address:port (String: 1).
```

### <a id="getit"></a>How to get latest Version

Cedi Config supports Scala 2.11 and 2.12. It is published to Maven Central.

```scala
libraryDependencies += "com.ccadllc.cedi" %% "config" % "1.0.0"
```

## Copyright and License

This project is made available under the [Apache License, Version 2.0](LICENSE). Copyright information can be found in [NOTICE](NOTICE).

