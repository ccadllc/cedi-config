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

/**
 * A key in a Typesafe `Config` object.
 *
 * Keys may be absolute or relative. Relative keys are relative to a specific `Config` object,
 * which may be arbitrarily nested inside other config keys. An absolute key refers to a path
 * from the root of the config tree.
 */
sealed abstract class ConfigKey extends Product with Serializable {

  /**
   * Indicates whether or not this key is absolute or relative.
   *
   * @return true if absolute, false otherwise
   */
  final def isAbsolute: Boolean = this match {
    case _: ConfigKey.Absolute => true
    case _: ConfigKey.Relative => false
  }

  /**
   * Returns a new config key equal to this key prefixed by the supplied value,
   * if this key is relative. If this key is absolute, this method has no effect.
   *
   * @param p prefix
   * @return prefixed key (if this key is relative), this otherwise
   */
  final def prefix(p: String): ConfigKey = this match {
    case _: ConfigKey.Absolute => this
    case ConfigKey.Relative(v) => ConfigKey.Relative(s"$p.$v")
  }

  /**
   * Returns a new config key that is marked absolute. If this key is already
   * absolute, then this method returns `this`.
   *
   * @return absolute key
   */
  final def absolute: ConfigKey = this match {
    case _: ConfigKey.Absolute => this
    case ConfigKey.Relative(v) => ConfigKey.Absolute(v)
  }

  override final def toString: String = this match {
    case ConfigKey.Absolute(v) => v
    case ConfigKey.Relative(v) => v
  }
}

/** Companion for [[ConfigKey]]. */
object ConfigKey {

  /** Constructor for an absolute key. */
  final case class Absolute(value: String) extends ConfigKey

  /** Constructor for a relative key. */
  final case class Relative(value: String) extends ConfigKey
}
