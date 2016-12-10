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
package com.ccadllc.cedi

package object config {

  // This trick is from FS2, is licensed under the MIT License, and is copyright Paul Chiusano and respective contributors
  // From: https://github.com/functional-streams-for-scala/fs2/blob/04437cb0c6d3071b3dc45813bcb6041392a049ad/core/shared/src/main/scala/fs2/fs2.scala#L25-L40
  // Trick to get right-biased syntax for Either in 2.11 while retaining source compatibility with 2.12 and leaving
  // -Xfatal-warnings and -Xwarn-unused-imports enabled. Delete when no longer supporting 2.11.
  private[config] implicit class EitherSyntax[L, R](val self: Either[L, R]) extends AnyVal {
    def map[R2](f: R => R2): Either[L, R2] = self match {
      case Right(r) => Right(f(r))
      case l @ Left(_) => l.asInstanceOf[Either[L, R2]]
    }
    def flatMap[R2](f: R => Either[L, R2]): Either[L, R2] = self match {
      case Right(r) => f(r)
      case l @ Left(_) => l.asInstanceOf[Either[L, R2]]
    }
    def toOption: Option[R] = self match {
      case Right(r) => Some(r)
      case l @ Left(_) => None
    }
  }
}
