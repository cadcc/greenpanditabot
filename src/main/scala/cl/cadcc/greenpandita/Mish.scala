package cl.cadcc.greenpandita

import cats.implicits.*
import cats.syntax.all.*
import cats.MonadError
import fs2.Stream
import fs2.Stream.monadInstance

def repeat[F[_], O](x: Stream[F, O]): Stream[F, O] =
  x ++ repeat[F, O](x)

def drain[F[_], O](x: Stream[F, O]): Stream[F, Nothing] =
  x.flatMap {_ => Stream.empty}

def exec[F[_], O](fo: F[O]): Stream[F, Nothing] =
  drain(Stream.eval(fo))

def attempt[F[_], O](x: Stream[F, O]): Stream[F, Either[Throwable, O]] =
  x.map(_.asRight[Throwable]).handleErrorWith(th => Stream.emit(th.asLeft[O]))
