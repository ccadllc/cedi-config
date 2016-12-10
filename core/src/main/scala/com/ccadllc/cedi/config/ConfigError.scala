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

import com.typesafe.config.ConfigOrigin

/** Error which occurred when parsing a `Config`. */
sealed abstract class ConfigError {

  /** Key for which parsing failed. */
  def key: ConfigKey

  /** Origin describing where in config this error occurred. */
  def origin: Option[ConfigOrigin]

  /** Gets a humanized string describing this error. */
  def humanized: String = this match {
    case ConfigError.Missing(key) => s"No value for required key $key."
    case ConfigError.WrongType(key, hint, origin) =>
      val org = origin.map { o => s" (${o.description})." }.getOrElse(".")
      s"Wrong type for key $key - $hint$org"
    case ConfigError.BadValue(key, hint, origin) =>
      val org = origin.map { o => s" (${o.description})." }.getOrElse(".")
      s"Bad value for key $key - $hint$org"
    case ConfigError.General(key, hint, origin) =>
      val org = origin.map { o => s" (${o.description})." }.getOrElse(".")
      s"Error processing $key - $hint$org"
  }

  /** Returns a copy of this config error with the specified value prefixed on the key. */
  def prependKey(keyPrefix: String): ConfigError = this match {
    case e: ConfigError.Missing => e.copy(key = key.prefix(keyPrefix))
    case e: ConfigError.WrongType => e.copy(key = key.prefix(keyPrefix))
    case e: ConfigError.BadValue => e.copy(key = key.prefix(keyPrefix))
    case e: ConfigError.General => e.copy(key = key.prefix(keyPrefix))
  }

  /** Returns a copy of this config error that ignores requests to prepend keys. */
  def withAbsoluteKey: ConfigError = this match {
    case e: ConfigError.Missing => e.copy(key = key.absolute)
    case e: ConfigError.WrongType => e.copy(key = key.absolute)
    case e: ConfigError.BadValue => e.copy(key = key.absolute)
    case e: ConfigError.General => e.copy(key = key.absolute)
  }
}

/** Companion for [[ConfigError]]. */
object ConfigError {

  /** Indiciates the specified key was missing from the configuration. */
  final case class Missing(key: ConfigKey) extends ConfigError {
    val origin = None
  }

  /** Indicates the specified key existed but was of the wrong type. */
  final case class WrongType(key: ConfigKey, hint: String, origin: Option[ConfigOrigin]) extends ConfigError

  /** Indicates the specified key existed but contained a bad value. */
  final case class BadValue(key: ConfigKey, hint: String, origin: Option[ConfigOrigin]) extends ConfigError

  /** Indicates a general error with the specified key. */
  final case class General(key: ConfigKey, hint: String, origin: Option[ConfigOrigin]) extends ConfigError
}
