/*
 * Copyright (C) 2020-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.testkit.query.scaladsl
import akka.NotUsed
import akka.actor.ExtendedActorSystem
import akka.persistence.journal.Tagged
import akka.persistence.query.NoOffset
import akka.persistence.query.Offset
import akka.persistence.query.scaladsl.{
  CurrentEventsByPersistenceIdQuery,
  CurrentEventsByTagQuery,
  EventsByPersistenceIdQuery,
  PagedPersistenceIdsQuery,
  ReadJournal
}
import akka.persistence.query.{ EventEnvelope, Sequence }
import akka.persistence.testkit.EventStorage
import akka.persistence.testkit.internal.InMemStorageExtension
import akka.persistence.testkit.query.internal.EventsByPersistenceIdStage
import akka.stream.scaladsl.Source
import akka.util.unused
import com.typesafe.config.Config
import org.slf4j.LoggerFactory

object PersistenceTestKitReadJournal {
  val Identifier = "akka.persistence.testkit.query"
}

final class PersistenceTestKitReadJournal(system: ExtendedActorSystem, @unused config: Config, configPath: String)
    extends ReadJournal
    with EventsByPersistenceIdQuery
    with CurrentEventsByPersistenceIdQuery
    with CurrentEventsByTagQuery
    with PagedPersistenceIdsQuery {

  private val log = LoggerFactory.getLogger(getClass)

  private val storage: EventStorage = {
    // use shared path up to before `query` to identify which inmem journal we are addressing
    val storagePluginId = configPath.replaceAll("""query$""", "journal")
    log.debug("Using in memory storage [{}] for test kit read journal", storagePluginId)
    InMemStorageExtension(system).storageFor(storagePluginId)
  }

  private def unwrapTaggedPayload(payload: Any): Any = payload match {
    case Tagged(payload, _) => payload
    case payload            => payload
  }

  override def eventsByPersistenceId(
      persistenceId: String,
      fromSequenceNr: Long = 0,
      toSequenceNr: Long = Long.MaxValue): Source[EventEnvelope, NotUsed] = {
    Source.fromGraph(new EventsByPersistenceIdStage(persistenceId, fromSequenceNr, toSequenceNr, storage))
  }

  override def currentEventsByPersistenceId(
      persistenceId: String,
      fromSequenceNr: Long = 0,
      toSequenceNr: Long = Long.MaxValue): Source[EventEnvelope, NotUsed] = {
    Source(storage.tryRead(persistenceId, fromSequenceNr, toSequenceNr, Long.MaxValue)).map { pr =>
      EventEnvelope(
        Sequence(pr.sequenceNr),
        persistenceId,
        pr.sequenceNr,
        unwrapTaggedPayload(pr.payload),
        pr.timestamp,
        pr.metadata)
    }
  }

  override def currentEventsByTag(tag: String, offset: Offset = NoOffset): Source[EventEnvelope, NotUsed] = {
    offset match {
      case NoOffset =>
      case _ =>
        throw new UnsupportedOperationException("Offsets not supported for persistence test kit currentEventsByTag yet")
    }
    Source(storage.tryReadByTag(tag)).map { pr =>
      EventEnvelope(
        Sequence(pr.sequenceNr),
        pr.persistenceId,
        pr.sequenceNr,
        unwrapTaggedPayload(pr.payload),
        pr.timestamp,
        pr.metadata)
    }
  }

  /**
   * Get the current persistence ids.
   *
   * Not all plugins may support in database paging, and may simply use drop/take Akka streams operators
   * to manipulate the result set according to the paging parameters.
   *
   * @param afterId The ID to start returning results from, or [[None]] to return all ids. This should be an id
   *                returned from a previous invocation of this command. Callers should not assume that ids are
   *                returned in sorted order.
   * @param limit   The maximum results to return. Use Long.MaxValue to return all results. Must be greater than zero.
   * @return A source containing all the persistence ids, limited as specified.
   */
  override def currentPersistenceIds(afterId: Option[String], limit: Long): Source[String, NotUsed] =
    storage.currentPersistenceIds(afterId, limit)
}
