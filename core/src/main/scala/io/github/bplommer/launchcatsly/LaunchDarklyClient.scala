/*
 * Copyright 2022 Ben Plommer
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

package io.github.bplommer.launchcatsly

import cats.effect.std.{Dispatcher, Queue}
import cats.effect.{Async, Resource}
import cats.~>
import com.launchdarkly.sdk.server.interfaces.{FlagValueChangeEvent, FlagValueChangeListener}
import com.launchdarkly.sdk.server.{LDClient, LDConfig}
import com.launchdarkly.sdk.{LDUser, LDValue}
import fs2._

trait LaunchDarklyClient[F[_]] {
  def boolVariation(featureKey: String, user: LDUser, defaultValue: Boolean): F[Boolean]

  def stringVariation(featureKey: String, user: LDUser, defaultValue: String): F[String]

  def intVariation(featureKey: String, user: LDUser, defaultValue: Int): F[Int]

  def doubleVariation(featureKey: String, user: LDUser, defaultValue: Double): F[Double]

  def jsonVariation(featureKey: String, user: LDUser, defaultValue: LDValue): F[LDValue]

  def listen(featureKey: String, user: LDUser): Stream[F, FlagValueChangeEvent]

  def flush: F[Unit]

  def mapK[G[_]](fk: F ~> G): LaunchDarklyClient[G]
}

object LaunchDarklyClient {
  def resource[F[_]](sdkKey: String, config: LDConfig)(implicit
      F: Async[F]
  ): Resource[F, LaunchDarklyClient[F]] =
    Resource
      .fromAutoCloseable(F.blocking(new LDClient(sdkKey, config)))
      .map { ldClient =>
        new LaunchDarklyClient.Default[F] {

          override def unsafeWithJavaClient[A](f: LDClient => A): F[A] =
            F.blocking(f(ldClient))

          override def listen(featureKey: String, user: LDUser): Stream[F, FlagValueChangeEvent] =
            Stream.eval(F.delay(ldClient.getFlagTracker)).flatMap { tracker =>
              Stream.resource(Dispatcher[F]).flatMap { dispatcher =>
                Stream.eval(Queue.unbounded[F, FlagValueChangeEvent]).flatMap { q =>
                  val listener = new FlagValueChangeListener {
                    override def onFlagValueChange(event: FlagValueChangeEvent): Unit =
                      dispatcher.unsafeRunSync(q.offer(event))
                  }

                  Stream.bracket(
                    F.delay(tracker.addFlagValueChangeListener(featureKey, user, listener))
                  )(listener => F.delay(tracker.removeFlagChangeListener(listener))) >>
                    Stream.fromQueueUnterminated(q)
                }
              }
            }
        }
      }

  trait Default[F[_]] extends LaunchDarklyClient[F] {
    self =>
    protected def unsafeWithJavaClient[A](f: LDClient => A): F[A]

    override def boolVariation(featureKey: String, user: LDUser, default: Boolean): F[Boolean] =
      unsafeWithJavaClient(_.boolVariation(featureKey, user, default))

    override def stringVariation(featureKey: String, user: LDUser, default: String): F[String] =
      unsafeWithJavaClient(_.stringVariation(featureKey, user, default))

    override def intVariation(featureKey: String, user: LDUser, default: Int): F[Int] =
      unsafeWithJavaClient(_.intVariation(featureKey, user, default))

    override def doubleVariation(featureKey: String, user: LDUser, default: Double): F[Double] =
      unsafeWithJavaClient(_.doubleVariation(featureKey, user, default))

    override def jsonVariation(featureKey: String, user: LDUser, default: LDValue): F[LDValue] =
      unsafeWithJavaClient(_.jsonValueVariation(featureKey, user, default))

    override def flush: F[Unit] = unsafeWithJavaClient(_.flush())

    override def mapK[G[_]](fk: F ~> G): LaunchDarklyClient[G] = new LaunchDarklyClient.Default[G] {
      override def unsafeWithJavaClient[A](f: LDClient => A): G[A] = fk(
        self.unsafeWithJavaClient(f)
      )

      override def listen(featureKey: String, user: LDUser): Stream[G, FlagValueChangeEvent] =
        self.listen(featureKey, user).translate(fk)

      override def flush: G[Unit] = fk(self.flush)
    }
  }
}
