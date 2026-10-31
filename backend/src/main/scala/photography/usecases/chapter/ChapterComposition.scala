package photography.usecases.chapter

import cats.data.NonEmptyList
import cats.effect.kernel.MonadCancelThrow
import cats.effect.std.Mutex
import cats.syntax.all.*
import org.typelevel.log4cats.Logger
import photography.capabilities.Transaction
import photography.domain.album.{AlbumId, ChapterId}
import photography.domain.layout.SpreadId
import photography.ports.album.AlbumCompositionRepository
import photography.ports.spread.SpreadRepository

trait ChapterComposition[F[_]]:
  def replace(album: AlbumId, chapter: ChapterId, expected: NonEmptyList[SpreadId], order: NonEmptyList[SpreadId]): F[ChapterComposition.Result]

object ChapterComposition:
  def make[F[_]: MonadCancelThrow: Mutex: Logger, Db[_]: cats.MonadThrow](
      composition: AlbumCompositionRepository[Db],
      spreads: SpreadRepository[Db]
  )(using Transaction[Db, F]): ChapterComposition[F] = new LiveChapterComposition(composition, spreads)

  final private class LiveChapterComposition[F[_]: MonadCancelThrow: Mutex: Logger, Db[_]: cats.MonadThrow](
      composition: AlbumCompositionRepository[Db],
      spreads: SpreadRepository[Db]
  )(using Transaction[Db, F]) extends ChapterComposition[F]:
    override def replace(album: AlbumId, chapter: ChapterId, expected: NonEmptyList[SpreadId], order: NonEmptyList[SpreadId]): F[Result] =
      val valid = order.head.value === expected.head.value &&
        order.toList.map(_.value).distinct.size === order.length &&
        order.toList.map(_.value).toSet === expected.toList.map(_.value).toSet
      if !valid then Result.Invalid.pure[F]
      else
        summon[Mutex[F]].lock.surround:
          MonadCancelThrow[F].uncancelable: _ =>
            val mutation = composition.findChapterOrder(album, chapter).flatMap:
              case None                                                                          => (Result.NotFound: Result).pure[Db]
              case Some(current) if current.toList.map(_.value) =!= expected.toList.map(_.value) => (Result.Stale: Result).pure[Db]
              case Some(_) => spreads.reorder(chapter, order).as(Result.Saved: Result)
            Transaction[Db, F].run(mutation).handleErrorWith(error =>
              Logger[F].error(error)("Composition write acknowledgement failed").as(Result.Uncertain)
            )

  enum Result:
    case Saved, Invalid, NotFound, Stale, Uncertain
