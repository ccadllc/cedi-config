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

/** Non-empty list of configuration errors. */
final case class ConfigErrors(head: ConfigError, tail: List[ConfigError]) {

  /** Gets a printable description of all configuration errors. */
  def description: String = {
    ("Failed to parse configuration." :: toList.map { e => "  " + e.humanized }).mkString(String.format("%n"))
  }

  /**
   * Returns a new `ConfigErrors` collection consisting of all errors in this collection followed by all
   * errors in `that` collection.
   *
   * @param that errors to concatenate
   * @return concatenated errors
   */
  def ++(that: ConfigErrors): ConfigErrors =
    ConfigErrors(head, tail ++ that.toList)

  /**
   * Modifies each error in this collection using the supplied function.
   *
   * @param f function to apply
   * @return new collection
   */
  def map(f: ConfigError => ConfigError): ConfigErrors =
    ConfigErrors(f(head), tail.map(f))

  /**
   * Returns a new `ConfigErrors` consisting of the errors from this collection which satisfy the supplied
   * predicate.
   *
   * @param p predicate
   * @return none if no errors matched predicate, some otherwise
   */
  def filter(p: ConfigError => Boolean): Option[ConfigErrors] = {
    val res = toList.filter(p)
    res.headOption.map { head => ConfigErrors(head, res.drop(1)) }
  }

  /** Converts this collection to a list. */
  def toList: List[ConfigError] = head :: tail

  /**
   * Returns a new `ConfigErrors` with each constituent error updated with an absolute key.
   *
   * @param return new collection
   */
  def withAbsoluteKeys: ConfigErrors = ConfigErrors(head.withAbsoluteKey, tail.map(_.withAbsoluteKey))

  override def toString: String = toList.mkString("ConfigErrors(", ", ", ")")
}

/** Companion for [[ConfigErrors]]. */
object ConfigErrors {

  /** Creates a `ConfigErrors` from the supplied values. */
  def of(head: ConfigError, tail: ConfigError*): ConfigErrors =
    ConfigErrors(head, tail.toList)
}
