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

import scala.collection.JavaConverters._
import scala.concurrent.duration.FiniteDuration

import com.typesafe.config.{ Config, ConfigException, ConfigFactory, ConfigMemorySize, ConfigValue }
import shapeless.{ ::, HList, HNil, Generic, LabelledGeneric, Lazy, Typeable }

/**
 * Parses a value of type `A` from a Typesafe `Config` object.
 */
sealed abstract class ConfigParser[+A] { self =>

  /**
   * Parses a value of type `A` from `config`, returning the parsed value or a non-empty list of errors
   * that occurred while parsing.
   *
   * @param config config to parse
   * @return errors or parsed value
   */
  def parse(config: Config): Either[ConfigErrors, A]

  /**
   * Creates a new parser that applies `f` to the parsed `A` after it has been parsed.
   *
   * @param f function to apply
   * @return new parser
   */
  def map[B](f: A => B): ConfigParser[B] =
    ConfigParser(s"${this}.map(<function>)") { cfg => parse(cfg).map(f) }

  /**
   * Like `map` but the `toString` of the returned parser is the same as this parser's `toString`.
   *
   * @param f function to apply
   * @return new parser
   */
  def mapPreservingToString[B](f: A => B): ConfigParser[B] =
    ConfigParser(this.toString) { cfg => parse(cfg).map(f) }

  /**
   * Creates a new parser from this parser and `that` parser, using the supplied `f`
   * to combined the parsed `A` and `B` in to a new value. Any errors that occurred
   * when parsing with both this and that are returned.
   *
   * @param that parser to combine with
   * @param f function that combines the result of this and that parsers
   * @return new parser
   */
  def map2[B, C](that: ConfigParser[B])(f: (A, B) => C): ConfigParser[C] =
    ConfigParser(s"${this}.map2(${that})(<function>)") { cfg =>
      val a = parse(cfg)
      val b = that.parse(cfg)
      (a, b) match {
        case (Left(e1), Left(e2)) => Left(e1 ++ e2)
        case (Left(e1), _) => Left(e1)
        case (_, Left(e2)) => Left(e2)
        case (Right(a), Right(b)) => Right(f(a, b))
      }
    }

  /**
   * Creates a new parser from this and `that` parser that parses both an `A` and a `B`
   * and returns the parsed values in a tuple. Any errors that occurred when parsing
   * with both this and that are returned.
   *
   * @param that parser to combine with
   * @return new parser
   */
  def tuple[B](that: ConfigParser[B]): ConfigParser[(A, B)] =
    map2(that)((a, b) => (a, b)).withToString(s"${this}.tuple(${that})")

  /**
   * Creates a new parser by examining the value of type `A` that this parser parses.
   *
   * Warning: using `bind` results in a loss of potential error messages -- consider: if this parser
   * cannot parse an `A`, the resulting error will be returned, and then necessarily, no errors
   * that may have been returned from the parser returned from `f(a)` will be reported. Because
   * of this, it is typically better to avoid use of `bind` when possible -- e.g., using `or`.
   *
   * Note: this is named `bind` instead of `flatMap` to discourage its use in for-comprehensions
   * and otherwise.
   *
   * @param f function that creates a new parser from the parsed `A`
   * @return new parser
   */
  def bind[B](f: A => ConfigParser[B]): ConfigParser[B] =
    ConfigParser(s"${this}.bind(<function>)") { cfg => parse(cfg).flatMap { a => f(a).parse(cfg) } }

  /**
   * Creates a new parser that marks any returned errors as having absolute keys -- that is, config keys
   * which are known to be non-relative, and hence, should not be adjusted by combinators like `under`.
   *
   * @return new parser
   */
  def withAbsoluteErrorKeys: ConfigParser[A] =
    ConfigParser(s"${this}.withAbsoluteErrorKeys") { cfg =>
      parse(cfg) match {
        case Left(errs) => Left(errs.withAbsoluteKeys)
        case r @ Right(_) => r
      }
    }

  /**
   * Creates a new parser from this parser that successfully parses `None` if any config keys are missing
   * when parsing with this parser and wraps successful results in `Some`.
   *
   * @return new parser
   */
  def optional: ConfigParser[Option[A]] =
    ConfigParser(s"${this}.optional") { cfg =>
      parse(cfg) match {
        case Left(errs) =>
          val withoutMissing = errs.filter {
            case ConfigError.Missing(_) => false
            case _ => true
          }
          withoutMissing match {
            case None => Right(None)
            case Some(errs) => Left(errs)
          }
        case Right(a) => Right(Some(a))
      }
    }

  /**
   * Alias for `optional.map(_.getOrElse(default))`.
   *
   * @return new parser
   */
  def withFallbackDefault[A2 >: A](default: => A2): ConfigParser[A2] =
    optional.map(_.getOrElse(default))

  /**
   * Creates a new parser that first tries to parse with this parser and if that fails, tries to parse
   * with the supplied `that` parser.
   *
   * @param that parser to try if parsing with `this` fails
   * @return new parser
   */
  def or[A2 >: A](that: ConfigParser[A2]): ConfigParser[A2] =
    ConfigParser(s"${this}.or(${that})") { cfg =>
      parse(cfg) match {
        case r @ Right(_) => r
        case Left(e) => that.parse(cfg)
      }
    }

  /**
   * Like `or` but maintains an indication as to which parser succeeded -- successful results from
   * `this` are wrapped in `Left` and successful results from `that` are wrapped in `Right`.
   *
   * @param that parser to try if parsing with `this` fails
   * @return new parser
   */
  def either[B](that: ConfigParser[B]): ConfigParser[Either[A, B]] =
    map(Left.apply).or(that.map(Right.apply)).withToString(s"${this}.either(${that})")

  /**
   * Alias for `ConfigParser.subconfig(key, failfast)(this).`
   * @param key config key at which to place this parser
   * @return new parser
   * @see [[ConfigParser.subconfig]]
   */
  def under(key: String, failFast: Boolean = false): ConfigParser[A] =
    ConfigParser.subconfig(key, failFast)(this)

  protected def description: String

  override def toString: String = description

  /**
   * Creates a new config parser that behaves identically to this parser but
   * returns the supplied `s` from `toString`.
   *
   * @param s string to return from `toString` method
   * @return new parser
   */
  def withToString(s: String): ConfigParser[A] = new ConfigParser[A] {
    def parse(c: Config) = self.parse(c)
    val description = s
  }
}

/** Companion for [[ConfigParser]]. */
object ConfigParser {

  /**
   * Creates a parser from the supplied description and function.
   *
   * @param description description of the new parser -- used in `toString`
   * @param f function to lift to a parser
   * @return new parser
   */
  def apply[A](description: String)(f: Config => Either[ConfigErrors, A]): ConfigParser[A] = {
    val description0 = description
    new ConfigParser[A] {
      def parse(config: Config): Either[ConfigErrors, A] = f(config)
      val description = description0
    }
  }

  /**
   * Summons the `ConfigParser` in implicit scope.  A convenience for the equivalent
   * `implicitly[ConfigParser[A]]`.
   * @param cp the `ConfigParser` in implicit scope.
   * @return config parser in implicit scope,
   */
  def apply[A](implicit cp: ConfigParser[A]): ConfigParser[A] = cp

  /**
   * Creates a parser from the supplied function.
   * Consider using [[apply]] instead in order to get a useful `toString` method.
   *
   * @param f function to lift to a parser
   * @return new parser
   */
  def anonymous[A](f: Config => Either[ConfigErrors, A]): ConfigParser[A] =
    apply("anonymous(<function>)")(f)

  /**
   * Creates a parser from a supplied key and a function which converts a `ConfigValue` to an `A` or an
   * error message describing the failure of type conversion.
   *
   * @param key config key of the value to parse
   * @param f function which performs a type conversion from a `ConfigValue` to `A`, returning an error as a string in a `Left`
   * @return new parser
   */
  def fromConfigValue[A](key: String)(f: ConfigValue => Either[String, A]): ConfigParser[A] = {
    val k = ConfigKey.Relative(key)
    ConfigParser(s"fromConfigValue($key)") { cfg =>
      for {
        value <- try Right(cfg.getValue(key))
        catch { case e: ConfigException.Missing => Left(ConfigErrors.of(ConfigError.Missing(k))) }
        a <- f(value).swap.map { err => ConfigErrors.of(ConfigError.BadValue(k, err, Option(value.origin))) }.swap
      } yield a
    }
  }

  /**
   * Creates a parser from a supplied key and a function which converts a string to an `A` or an
   * error message describing the failure of type conversion.
   *
   * @param key config key of the value to parse
   * @param f function which performs a type conversion from a string to `A`, returning an error as a string in a `Left`
   * @return new parser
   */
  def fromString[A](key: String)(f: String => Either[String, A]): ConfigParser[A] = {
    fromConfigValue(key) { value =>
      for {
        str <- value.unwrapped match {
          case str: String => Right(str)
          case n: java.lang.Number => Right(n.toString)
          case b: java.lang.Boolean => Right(b.toString)
          case _ => Left("expected a scalar value")
        }
        a <- f(str)
      } yield a
    }
  }

  /**
   * Creates a parser that parses a list of elements using the supplied function to convert each value to type `A`.
   *
   * The list is extracted from the `Config` via `Config#getValue(key)` and safely cast to a `List[_]` (returning an
   * error on the left if `getValue` returns something other than a `List[_]`). Each value of the returned list is
   * then converted to type `A` using the supplied function.
   *
   * @param key config key of the value to parse
   * @param f function which performs a type conversion to `A`, returning an error as a string in a `Left`
   * @return new parser
   */
  def fromList[A](key: String)(f: Any => Either[String, A]): ConfigParser[List[A]] = {
    val k = ConfigKey.Relative(key)
    ConfigParser(s"fromList($key)") { cfg =>
      for {
        value <- try Right(cfg.getValue(key))
        catch { case e: ConfigException.Missing => Left(ConfigErrors.of(ConfigError.Missing(k))) }
        values <- value.unwrapped match {
          case xs: java.util.List[_] => Right(xs.asScala.toList)
          case e => Left(ConfigErrors.of(ConfigError.WrongType(k, "expected list of values", Option(value.origin))))
        }
        a <- traverseListEither(values.zipWithIndex) {
          case (v, idx) =>
            f(v) match {
              case Left(err) => Left(ConfigErrors.of(ConfigError.BadValue(ConfigKey.Relative(s"$key[$idx]"), err, Option(value.origin))))
              case Right(r) => Right(r)
            }
        } { _ ++ _ }
      } yield a
    }
  }

  /**
   * Like `fromList` but each value is first converted to a string before being passed to the supplied type conversion function.
   *
   * @param key config key of the value to parse
   * @param f function which performs a type conversion to `A`, returning an error as a string in a `Left`
   * @return new parser
   */
  def fromStringList[A](key: String)(f: String => Either[String, A]): ConfigParser[List[A]] =
    fromList(key)(v => f(v.toString))

  /**
   * Creates a parser that ignores the `Config` object and always returns the supplied `A` value as a result from parsing.
   *
   * @param a value to lift in to a parser
   * @return new parser
   */
  def pure[A](a: A): ConfigParser[A] = ConfigParser(s"pure($a)") { _ => Right(a) }

  /**
   * Creates a parser that parses the the supplied key as a string (via `cfg.getString(key)`).
   *
   * @param key config key of the value to parse
   * @return new parser
   */
  def string(key: String): ConfigParser[String] = doGet(key, "string")(_.getString(key))

  /**
   * Creates a parser that parses the the supplied key as a list of strings.
   *
   * @param key config key of the value to parse
   * @return new parser
   */
  def stringList(key: String): ConfigParser[List[String]] = doGet(key, "string list")(_.getStringList(key).asScala.toList)

  /**
   * Creates a parser that parses the the supplied key as a string (via `cfg.getDouble(key)`).
   *
   * @param key config key of the value to parse
   * @return new parser
   */
  def double(key: String): ConfigParser[Double] = doGet(key, "double")(_.getDouble(key))

  /**
   * Creates a parser that parses the the supplied key as a list of doubles.
   *
   * @param key config key of the value to parse
   * @return new parser
   */
  def doubleList(key: String): ConfigParser[List[Double]] = doGet(key, "double list")(_.getDoubleList(key).asScala.toList.map(d => d: Double))

  /**
   * Creates a parser that parses the the supplied key as a `FiniteDuration` (via `cfg.getDuration(key)`).
   *
   * @param key config key of the value to parse
   * @return new parser
   */
  def duration(key: String): ConfigParser[FiniteDuration] = jduration(key).mapPreservingToString(javaDurationToFiniteDuration)

  /**
   * Creates a parser that parses the the supplied key as a list of `FiniteDuration`s.
   *
   * @param key config key of the value to parse
   * @return new parser
   */
  def durationList(key: String): ConfigParser[List[FiniteDuration]] = jdurationList(key).mapPreservingToString(_.map(javaDurationToFiniteDuration))

  private def javaDurationToFiniteDuration(d: java.time.Duration): FiniteDuration = {
    import scala.concurrent.duration._
    d.getSeconds.seconds + d.getNano.nanos
  }

  /**
   * Creates a parser that parses the the supplied key as a `java.time.Duration` (via `cfg.getDuration(key)`).
   *
   * @param key config key of the value to parse
   * @return new parser
   */
  def jduration(key: String): ConfigParser[java.time.Duration] = doGet(key, "duration")(_.getDuration(key))

  /**
   * Creates a parser that parses the the supplied key as a list of `java.time.Duration`s.
   *
   * @param key config key of the value to parse
   * @return new parser
   */
  def jdurationList(key: String): ConfigParser[List[java.time.Duration]] = doGet(key, "duration list")(_.getDurationList(key).asScala.toList)

  /**
   * Creates a parser that parses the the supplied key as a boolean (via `cfg.getBoolean(key)`).
   *
   * @param key config key of the value to parse
   * @return new parser
   */
  def bool(key: String): ConfigParser[Boolean] = doGet(key, "boolean")(_.getBoolean(key))

  /**
   * Creates a parser that parses the the supplied key as a list of booleans.
   *
   * @param key config key of the value to parse
   * @return new parser
   */
  def boolList(key: String): ConfigParser[List[Boolean]] = doGet(key, "boolean list")(_.getBooleanList(key).asScala.toList.map(b => b: Boolean))

  /**
   * Creates a parser that parses the the supplied key as an int (via `cfg.getInt(key)`).
   *
   * @param key config key of the value to parse
   * @return new parser
   */
  def int(key: String): ConfigParser[Int] = doGet(key, "int")(_.getInt(key))

  /**
   * Creates a parser that parses the the supplied key as a list of ints.
   *
   * @param key config key of the value to parse
   * @return new parser
   */
  def intList(key: String): ConfigParser[List[Int]] = doGet(key, "int list")(_.getIntList(key).asScala.toList.map(i => i: Int))

  /**
   * Creates a parser that parses the the supplied key as a long (via `cfg.getLong(key)`).
   *
   * @param key config key of the value to parse
   * @return new parser
   */
  def long(key: String): ConfigParser[Long] = doGet(key, "long")(_.getLong(key))

  /**
   * Creates a parser that parses the the supplied key as a list of longs.
   *
   * @param key config key of the value to parse
   * @return new parser
   */
  def longList(key: String): ConfigParser[List[Long]] = doGet(key, "long list")(_.getLongList(key).asScala.toList.map(l => l: Long))

  /**
   * Creates a parser that parses the the supplied key as a memory size (via `cfg.getMemorySize(key)`).
   *
   * @param key config key of the value to parse
   * @return new parser
   */
  def memorySize(key: String): ConfigParser[ConfigMemorySize] = doGet(key, "memory size")(_.getMemorySize(key))

  /**
   * Creates a parser that parses the the supplied key as a list of memory sizes.
   *
   * @param key config key of the value to parse
   * @return new parser
   */
  def memorySizeList(key: String): ConfigParser[List[ConfigMemorySize]] = doGet(key, "memory list")(_.getMemorySizeList(key).asScala.toList)

  /**
   * Creates a parser that parses the the supplied key as a `ConfigValue`.
   *
   * @param key config key of the value to parse
   * @return new parser
   */
  def value(key: String): ConfigParser[ConfigValue] = doGet(key, "value")(_.getValue(key))

  /**
   * Creates a parser that extracts a config list at the specified key and then parses each
   * `Config` object in the extracted config list using the supplied config parser.
   *
   * @param key config key of the config list to parse
   * @param cp config parser to use to parse each config element of the config list
   * @return new parser
   */
  def list[A](key: String)(cp: ConfigParser[A]): ConfigParser[List[A]] =
    ConfigParser(s"list($key, $cp)") { cfg =>
      doGet(key, "config list")(_.getConfigList(key).asScala.toList).parse(cfg).flatMap { configs =>
        traverseListEither(configs.zipWithIndex) { case (c, idx) => cp.parse(c).swap.map(_.map(_.prependKey(s"$key[$idx]"))).swap } { _ ++ _ }
      }
    }

  /**
   * Like [[list]] but returns results as a `Vector` instead of a `List`.
   *
   * @param key config key of the config list to parse
   * @param cp config parser to use to parse each config element of the config list
   * @return new parser
   */
  def vector[A](key: String)(cp: ConfigParser[A]): ConfigParser[Vector[A]] = list(key)(cp).map(_.toVector)

  private def doGet[A](key: String, tpe: String)(get: Config => A): ConfigParser[A] = {
    val k = ConfigKey.Relative(key)
    ConfigParser(s"""$tpe("$key")""") { cfg =>
      try Right(get(cfg))
      catch {
        case e: ConfigException.Missing => Left(ConfigErrors.of(ConfigError.Missing(k)))
        case e: ConfigException.WrongType => Left(ConfigErrors.of(ConfigError.WrongType(k, s"expected $tpe", Option(e.origin))))
        case e: ConfigException.BadValue => Left(ConfigErrors.of(ConfigError.BadValue(k, s"expected $tpe", Option(e.origin))))
      }
    }
  }

  /**
   * Creates a new parser that places the config parser `p` under key `key`.
   *
   * For example, if `p` parses config keys "foo" and "bar", then `subconfig("abc")` parses
   * config keys "abc.foo" and "abc.bar".
   *
   * The `failFast` parameter controls how missing keys are reported -- when `failFast` is false,
   * each individual missing key error from `p` is returned. When `failFast` is true, the existence
   * of `key` is checked before `p` gets a chance to parse. If `key` doesn't exist, a single missing
   * key error is returned.
   *
   * @param key config key to place `p` under
   * @param failFast specifies how missing key errors are reported
   * @param p config parser to place under `key`
   * @return new parser
   */
  def subconfig[A](key: String, failFast: Boolean = false)(p: ConfigParser[A]): ConfigParser[A] = {
    val k = ConfigKey.Relative(key)
    ConfigParser(s"""subconfig("$key", $failFast)($p)""") { cfg =>
      val sub: Option[() => Config] = {
        if (cfg.hasPath(key)) Some(() => cfg.getConfig(key))
        else if (!failFast) Some(() => ConfigFactory.empty(Option(cfg.origin).map(_.description).orNull))
        else None
      }
      sub match {
        case Some(subcfg) =>
          try p.parse(subcfg()).fold(errors => Left(errors.map(_.prependKey(key))), Right(_))
          catch {
            case e: ConfigException.WrongType => Left(ConfigErrors.of(ConfigError.WrongType(k, hint = "expected an object but got a value", Option(e.origin))))
          }
        case None =>
          Left(ConfigErrors.of(ConfigError.Missing(k)))
      }
    }
  }

  /**
   * Creates a parser that returns the configuration as the parsed value.
   *
   * @return new parser
   */
  def ask: ConfigParser[Config] = ConfigParser("ask") { cfg => Right(cfg) }

  /**
   * Creates a parser that returns the config at the specified key.
   *
   * @param key config key of the config to return
   * @return new parser
   */
  def config(key: String): ConfigParser[Config] = subconfig(key)(ask)

  /**
   * Creates a parser that returns the config list at the specified key (via `cfg.getConfigList(key)`).
   *
   * @param key config key of the config to return
   * @return new parser
   */
  def configList(key: String): ConfigParser[List[Config]] = doGet(key, "config list")(_.getConfigList(key).asScala.toList)

  /** Supports `::` syntax. */
  implicit class HListOps[L <: HList](private val self: ConfigParser[L]) extends AnyVal {
    /**
     * Creates a new parser that extends the `HList` by one element to the left.
     *
     * Behaves like `map2` with repsect to errors -- i.e., errors are accumulated.
     *
     * @param that element parser to extend with
     * @return new parser
     */
    def ::[A](that: ConfigParser[A]): ConfigParser[A :: L] = that.map2(self) { (a, l) => a :: l }.withToString(s"($that :: $self)")
  }

  /** Supports `::` syntax. */
  implicit class NonHListOps[A](private val self: ConfigParser[A]) extends AnyVal {
    /**
     * Creates a new parser that creates an `HList` parser of 2 elements.
     *
     * Behaves like `map2` with repsect to errors -- i.e., errors are accumulated.
     *
     * @param that element parser to extend with
     * @return new parser
     */
    def ::[B](that: ConfigParser[B]): ConfigParser[B :: A :: HNil] = that :: self.mapPreservingToString(_ :: HNil)
  }

  /** Supports `as` syntax. */
  implicit class AsOps[A](private val self: ConfigParser[A]) extends AnyVal {
    /**
     * Creates a new parser that converts the parsed representation type `A` in to an instance of case class `B` assuming
     * `A` is the generic representation (e.g., record) of `B`.
     *
     * @tparam B case class to convert to
     * @return new parser
     */
    def as[B](implicit as: As[A, B]): ConfigParser[B] = self.map(as.to).withToString(s"$self.as[${as.describeCodomain}]")
  }

  /** Type class that describes a total function `A => B` in support of the `as` combinator. */
  @annotation.implicitNotFound("""Could not prove that a value of ${A} can be converted to a value of ${B}.""")
  abstract class As[A, B] {

    /** Converts the supplied `A` to a value of type `B`. */
    def to(a: A): B

    /** Provides a friendly name for the target type. */
    def describeCodomain: String
  }

  /** Companion for [[As]]. */
  object As {

    /** Materializes an `As` instance that converts from a generic representation to the non-generic type `A`. */
    implicit def fromGeneric[A, Repr](implicit g: Generic.Aux[A, Repr], t: Typeable[A]): As[Repr, A] =
      new As[Repr, A] {
        def to(r: Repr) = g.from(r)
        def describeCodomain = t.describe
      }

    /** Materializes an `As` instance that converts from a labelled generic representation to the non-generic type `A`. */
    implicit def fromLabelledGeneric[A, Repr](implicit g: LabelledGeneric.Aux[A, Repr], t: Typeable[A]): As[Repr, A] =
      new As[Repr, A] {
        def to(r: Repr) = g.from(r)
        def describeCodomain = t.describe
      }
  }

  private def traverseListEither[A, L, R](xs: List[A])(f: A => Either[L, R])(combine: (L, L) => L): Either[L, List[R]] = {
    val results = xs.map(f)
    results.headOption.map { head =>
      val tail = results.drop(1)
      tail.foldLeft(head.map(List(_))) {
        case (Left(l1), Left(l2)) => Left(combine(l1, l2))
        case (Left(l1), Right(_)) => Left(l1)
        case (Right(_), Left(l2)) => Left(l2)
        case (Right(r1), Right(r2)) => Right(r2 :: r1)
      }.map(_.reverse)
    }.getOrElse(Right(Nil))
  }

  /**
   * Derives a parser from the structure of the specified case class.
   *
   * Requires an implicit `DerivedConfigFieldParser[x]` in scope for each component type `x` in the case class.
   *
   * @tparam A case class for which a parser should be derived for
   * @return derived parser
   */
  def derived[A](implicit d: DerivedConfigParser[A]): ConfigParser[A] = d.parser

  /** Supports [[ConfigParser.derived]]. */
  @annotation.implicitNotFound("""Could not derive a ConfigParser[${A}] - ensure there is an implicit ConfigParser[x] in scope for each component type x of ${A}.""")
  final case class DerivedConfigParser[A](parser: ConfigParser[A])

  /** Supports [[ConfigParser.derived]]. */
  object DerivedConfigParser {
    import shapeless._
    import shapeless.labelled._

    implicit val hnil: DerivedConfigParser[HNil] =
      DerivedConfigParser[HNil](ConfigParser("HNil") { cfg => Right(HNil) })

    implicit def hcons[K <: Symbol, V, T <: HList](implicit
      headKey: Witness.Aux[K],
      headParser: Lazy[DerivedConfigFieldParser[V]],
      tailParser: Lazy[DerivedConfigParser[T]]): DerivedConfigParser[FieldType[K, V] :: T] = {
      DerivedConfigParser[FieldType[K, V] :: T]({
        val configKey: String = {
          val raw = headKey.value.name
          val str = new StringBuilder
          var lastLower = true
          raw.foreach { c =>
            if (c.isLower) {
              lastLower = true
              str += c
            } else {
              if (lastLower) str += '-'
              lastLower = false
              str += c.toLower
            }
          }
          str.toString
        }
        headParser.value(configKey).mapPreservingToString(field[K](_)) :: tailParser.value.parser
      })
    }

    implicit def lgeneric[A, Repr](implicit lg: LabelledGeneric.Aux[A, Repr], t: Typeable[A], reprParser: DerivedConfigParser[Repr]): DerivedConfigParser[A] = {
      DerivedConfigParser[A](reprParser.parser.as[A])
    }
  }

  /** Supports [[ConfigParser.derived]]. */
  final case class DerivedConfigFieldParser[A](f: String => ConfigParser[A]) { self =>
    def apply(key: String): ConfigParser[A] = f(key)
    def map[B](f: A => B): DerivedConfigFieldParser[B] =
      DerivedConfigFieldParser[B](key => self.apply(key).map(f))
  }

  private[config] sealed trait DerivedConfigFieldParserLowPriority {
    implicit def fromImplicitParser[A](implicit instance: ConfigParser[A]): DerivedConfigFieldParser[A] = DerivedConfigFieldParser(key => subconfig(key)(instance))
    implicit def fromListImplicitParser[A](implicit instance: ConfigParser[A]): DerivedConfigFieldParser[List[A]] = DerivedConfigFieldParser(key => list(key)(instance))
    implicit def fromVectorImplicitParser[A](implicit instance: ConfigParser[A]): DerivedConfigFieldParser[Vector[A]] = DerivedConfigFieldParser(key => vector(key)(instance))
    implicit def fromImplicitDerivedConfigParser[A, Repr](implicit lg: LabelledGeneric.Aux[A, Repr], t: Typeable[A], parser: Lazy[DerivedConfigParser[Repr]]): DerivedConfigFieldParser[A] =
      DerivedConfigFieldParser(key ⇒ subconfig(key)(parser.value.parser.as[A]))
    implicit def fromListImplicitGenericDerivedParser[A, Repr](implicit lg: LabelledGeneric.Aux[A, Repr], t: Typeable[A], parser: Lazy[DerivedConfigParser[Repr]]): DerivedConfigFieldParser[List[A]] =
      DerivedConfigFieldParser(key ⇒ list(key)(parser.value.parser.as[A]))
    implicit def fromVectorImplicitGenericDerivedParser[A, Repr](implicit lg: LabelledGeneric.Aux[A, Repr], t: Typeable[A], parser: Lazy[DerivedConfigParser[Repr]]): DerivedConfigFieldParser[Vector[A]] =
      DerivedConfigFieldParser(key => vector(key)(parser.value.parser.as[A]))
  }

  /** Supports [[ConfigParser.derived]]. */
  object DerivedConfigFieldParser extends DerivedConfigFieldParserLowPriority {
    implicit val stringField: DerivedConfigFieldParser[String] = apply(string)
    implicit val stringListField: DerivedConfigFieldParser[List[String]] = apply(stringList)
    implicit val intField: DerivedConfigFieldParser[Int] = apply(int)
    implicit val intListField: DerivedConfigFieldParser[List[Int]] = apply(intList)
    implicit val longField: DerivedConfigFieldParser[Long] = apply(long)
    implicit val longListField: DerivedConfigFieldParser[List[Long]] = apply(longList)
    implicit val doubleField: DerivedConfigFieldParser[Double] = apply(double)
    implicit val doubleListField: DerivedConfigFieldParser[List[Double]] = apply(doubleList)
    implicit val boolField: DerivedConfigFieldParser[Boolean] = apply(bool)
    implicit val boolListField: DerivedConfigFieldParser[List[Boolean]] = apply(boolList)
    implicit val durationField: DerivedConfigFieldParser[FiniteDuration] = apply(duration)
    implicit val durationListField: DerivedConfigFieldParser[List[FiniteDuration]] = apply(durationList)
    implicit val jdurationField: DerivedConfigFieldParser[java.time.Duration] = apply(jduration)
    implicit val jdurationListField: DerivedConfigFieldParser[List[java.time.Duration]] = apply(jdurationList)
    implicit val memorySizeField: DerivedConfigFieldParser[ConfigMemorySize] = apply(memorySize)
    implicit val memorySizeListField: DerivedConfigFieldParser[List[ConfigMemorySize]] = apply(memorySizeList)
    implicit def optionalField[A](implicit a: DerivedConfigFieldParser[A]): DerivedConfigFieldParser[Option[A]] = apply(key => a(key).optional)
  }

  /** Provides `~` syntax. */
  implicit class Tuple22Builder[A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U](private val self: ConfigParser[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U)]) extends AnyVal {
    /** Creates a new parser that extends the tuple by one element to the right. */
    def ~[X](that: ConfigParser[X]): ConfigParser[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U, X)] =
      self.map2(that) { case ((a, b, c, d, e, f, g, h, i, j, k, l, m, n, o, p, q, r, s, t, u), x) => (a, b, c, d, e, f, g, h, i, j, k, l, m, n, o, p, q, r, s, t, u, x) }.
        withToString(s"($self ~ $that)")
  }

  /** Provides `~` syntax. */
  implicit class Tuple21Builder[A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T](private val self: ConfigParser[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T)]) extends AnyVal {
    /** Creates a new parser that extends the tuple by one element to the right. */
    def ~[X](that: ConfigParser[X]): ConfigParser[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, X)] =
      self.map2(that) { case ((a, b, c, d, e, f, g, h, i, j, k, l, m, n, o, p, q, r, s, t), x) => (a, b, c, d, e, f, g, h, i, j, k, l, m, n, o, p, q, r, s, t, x) }.
        withToString(s"($self ~ $that)")
  }

  /** Provides `~` syntax. */
  implicit class Tuple20Builder[A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S](private val self: ConfigParser[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S)]) extends AnyVal {
    /** Creates a new parser that extends the tuple by one element to the right. */
    def ~[X](that: ConfigParser[X]): ConfigParser[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, X)] =
      self.map2(that) { case ((a, b, c, d, e, f, g, h, i, j, k, l, m, n, o, p, q, r, s), x) => (a, b, c, d, e, f, g, h, i, j, k, l, m, n, o, p, q, r, s, x) }.
        withToString(s"($self ~ $that)")
  }

  /** Provides `~` syntax. */
  implicit class Tuple19Builder[A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R](private val self: ConfigParser[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R)]) extends AnyVal {
    /** Creates a new parser that extends the tuple by one element to the right. */
    def ~[X](that: ConfigParser[X]): ConfigParser[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, X)] =
      self.map2(that) { case ((a, b, c, d, e, f, g, h, i, j, k, l, m, n, o, p, q, r), x) => (a, b, c, d, e, f, g, h, i, j, k, l, m, n, o, p, q, r, x) }.
        withToString(s"($self ~ $that)")
  }

  /** Provides `~` syntax. */
  implicit class Tuple18Builder[A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q](private val self: ConfigParser[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q)]) extends AnyVal {
    /** Creates a new parser that extends the tuple by one element to the right. */
    def ~[X](that: ConfigParser[X]): ConfigParser[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, X)] =
      self.map2(that) { case ((a, b, c, d, e, f, g, h, i, j, k, l, m, n, o, p, q), x) => (a, b, c, d, e, f, g, h, i, j, k, l, m, n, o, p, q, x) }.
        withToString(s"($self ~ $that)")
  }

  /** Provides `~` syntax. */
  implicit class Tuple17Builder[A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P](private val self: ConfigParser[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P)]) extends AnyVal {
    /** Creates a new parser that extends the tuple by one element to the right. */
    def ~[X](that: ConfigParser[X]): ConfigParser[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, X)] =
      self.map2(that) { case ((a, b, c, d, e, f, g, h, i, j, k, l, m, n, o, p), x) => (a, b, c, d, e, f, g, h, i, j, k, l, m, n, o, p, x) }.
        withToString(s"($self ~ $that)")
  }

  /** Provides `~` syntax. */
  implicit class Tuple16Builder[A, B, C, D, E, F, G, H, I, J, K, L, M, N, O](private val self: ConfigParser[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O)]) extends AnyVal {
    /** Creates a new parser that extends the tuple by one element to the right. */
    def ~[X](that: ConfigParser[X]): ConfigParser[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, X)] =
      self.map2(that) { case ((a, b, c, d, e, f, g, h, i, j, k, l, m, n, o), x) => (a, b, c, d, e, f, g, h, i, j, k, l, m, n, o, x) }.
        withToString(s"($self ~ $that)")
  }

  /** Provides `~` syntax. */
  implicit class Tuple15Builder[A, B, C, D, E, F, G, H, I, J, K, L, M, N](private val self: ConfigParser[(A, B, C, D, E, F, G, H, I, J, K, L, M, N)]) extends AnyVal {
    /** Creates a new parser that extends the tuple by one element to the right. */
    def ~[X](that: ConfigParser[X]): ConfigParser[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, X)] =
      self.map2(that) { case ((a, b, c, d, e, f, g, h, i, j, k, l, m, n), x) => (a, b, c, d, e, f, g, h, i, j, k, l, m, n, x) }.
        withToString(s"($self ~ $that)")
  }

  /** Provides `~` syntax. */
  implicit class Tuple14Builder[A, B, C, D, E, F, G, H, I, J, K, L, M](private val self: ConfigParser[(A, B, C, D, E, F, G, H, I, J, K, L, M)]) extends AnyVal {
    /** Creates a new parser that extends the tuple by one element to the right. */
    def ~[X](that: ConfigParser[X]): ConfigParser[(A, B, C, D, E, F, G, H, I, J, K, L, M, X)] =
      self.map2(that) { case ((a, b, c, d, e, f, g, h, i, j, k, l, m), x) => (a, b, c, d, e, f, g, h, i, j, k, l, m, x) }.
        withToString(s"($self ~ $that)")
  }

  /** Provides `~` syntax. */
  implicit class Tuple13Builder[A, B, C, D, E, F, G, H, I, J, K, L](private val self: ConfigParser[(A, B, C, D, E, F, G, H, I, J, K, L)]) extends AnyVal {
    /** Creates a new parser that extends the tuple by one element to the right. */
    def ~[X](that: ConfigParser[X]): ConfigParser[(A, B, C, D, E, F, G, H, I, J, K, L, X)] =
      self.map2(that) { case ((a, b, c, d, e, f, g, h, i, j, k, l), x) => (a, b, c, d, e, f, g, h, i, j, k, l, x) }.
        withToString(s"($self ~ $that)")
  }

  /** Provides `~` syntax. */
  implicit class Tuple12Builder[A, B, C, D, E, F, G, H, I, J, K](private val self: ConfigParser[(A, B, C, D, E, F, G, H, I, J, K)]) extends AnyVal {
    /** Creates a new parser that extends the tuple by one element to the right. */
    def ~[X](that: ConfigParser[X]): ConfigParser[(A, B, C, D, E, F, G, H, I, J, K, X)] =
      self.map2(that) { case ((a, b, c, d, e, f, g, h, i, j, k), x) => (a, b, c, d, e, f, g, h, i, j, k, x) }.
        withToString(s"($self ~ $that)")
  }

  /** Provides `~` syntax. */
  implicit class Tuple11Builder[A, B, C, D, E, F, G, H, I, J](private val self: ConfigParser[(A, B, C, D, E, F, G, H, I, J)]) extends AnyVal {
    /** Creates a new parser that extends the tuple by one element to the right. */
    def ~[X](that: ConfigParser[X]): ConfigParser[(A, B, C, D, E, F, G, H, I, J, X)] =
      self.map2(that) { case ((a, b, c, d, e, f, g, h, i, j), x) => (a, b, c, d, e, f, g, h, i, j, x) }.
        withToString(s"($self ~ $that)")
  }

  /** Provides `~` syntax. */
  implicit class Tuple10Builder[A, B, C, D, E, F, G, H, I](private val self: ConfigParser[(A, B, C, D, E, F, G, H, I)]) extends AnyVal {
    /** Creates a new parser that extends the tuple by one element to the right. */
    def ~[X](that: ConfigParser[X]): ConfigParser[(A, B, C, D, E, F, G, H, I, X)] =
      self.map2(that) { case ((a, b, c, d, e, f, g, h, i), x) => (a, b, c, d, e, f, g, h, i, x) }.
        withToString(s"($self ~ $that)")
  }

  /** Provides `~` syntax. */
  implicit class Tuple9Builder[A, B, C, D, E, F, G, H](private val self: ConfigParser[(A, B, C, D, E, F, G, H)]) extends AnyVal {
    /** Creates a new parser that extends the tuple by one element to the right. */
    def ~[X](that: ConfigParser[X]): ConfigParser[(A, B, C, D, E, F, G, H, X)] =
      self.map2(that) { case ((a, b, c, d, e, f, g, h), x) => (a, b, c, d, e, f, g, h, x) }.
        withToString(s"($self ~ $that)")
  }

  /** Provides `~` syntax. */
  implicit class Tuple8Builder[A, B, C, D, E, F, G](private val self: ConfigParser[(A, B, C, D, E, F, G)]) extends AnyVal {
    /** Creates a new parser that extends the tuple by one element to the right. */
    def ~[X](that: ConfigParser[X]): ConfigParser[(A, B, C, D, E, F, G, X)] =
      self.map2(that) { case ((a, b, c, d, e, f, g), x) => (a, b, c, d, e, f, g, x) }.
        withToString(s"($self ~ $that)")
  }

  /** Provides `~` syntax. */
  implicit class Tuple7Builder[A, B, C, D, E, F](private val self: ConfigParser[(A, B, C, D, E, F)]) extends AnyVal {
    /** Creates a new parser that extends the tuple by one element to the right. */
    def ~[X](that: ConfigParser[X]): ConfigParser[(A, B, C, D, E, F, X)] =
      self.map2(that) { case ((a, b, c, d, e, f), x) => (a, b, c, d, e, f, x) }.
        withToString(s"($self ~ $that)")
  }

  /** Provides `~` syntax. */
  implicit class Tuple6Builder[A, B, C, D, E](private val self: ConfigParser[(A, B, C, D, E)]) extends AnyVal {
    /** Creates a new parser that extends the tuple by one element to the right. */
    def ~[X](that: ConfigParser[X]): ConfigParser[(A, B, C, D, E, X)] =
      self.map2(that) { case ((a, b, c, d, e), x) => (a, b, c, d, e, x) }.
        withToString(s"($self ~ $that)")
  }

  /** Provides `~` syntax. */
  implicit class Tuple5Builder[A, B, C, D](private val self: ConfigParser[(A, B, C, D)]) extends AnyVal {
    /** Creates a new parser that extends the tuple by one element to the right. */
    def ~[X](that: ConfigParser[X]): ConfigParser[(A, B, C, D, X)] =
      self.map2(that) { case ((a, b, c, d), x) => (a, b, c, d, x) }.
        withToString(s"($self ~ $that)")
  }

  /** Provides `~` syntax. */
  implicit class Tuple4Builder[A, B, C](private val self: ConfigParser[(A, B, C)]) extends AnyVal {
    /** Creates a new parser that extends the tuple by one element to the right. */
    def ~[X](that: ConfigParser[X]): ConfigParser[(A, B, C, X)] =
      self.map2(that) { case ((a, b, c), x) => (a, b, c, x) }.
        withToString(s"($self ~ $that)")
  }

  /** Provides `~` syntax. */
  implicit class Tuple3Builder[A, B](private val self: ConfigParser[(A, B)]) extends AnyVal {
    /** Creates a new parser that extends the tuple by one element to the right. */
    def ~[X](that: ConfigParser[X]): ConfigParser[(A, B, X)] =
      self.map2(that) { case ((a, b), x) => (a, b, x) }.
        withToString(s"($self ~ $that)")
  }

  /** Provides `~` syntax. */
  implicit class Tuple2Builder[A](private val self: ConfigParser[A]) extends AnyVal {
    /** Alias for [[ConfigParser#tuple]]. */
    def ~[X](that: ConfigParser[X]): ConfigParser[(A, X)] =
      self.map2(that)((a, x) => (a, x)).
        withToString(s"($self ~ $that)")
  }
}
