/*
 * PersistentDocumentSetCreationJob.scala
 *
 * Overview Project
 * Created by Jonas Karlsson, Aug 2012
 */

package persistence

import org.overviewproject.tree.orm.DocumentSetCreationJobState._

/**
 * Contains attributes of a DocumentSetCreationJob
 * and allows updates and deletions.
 */
trait PersistentDocumentSetCreationJob {

  val documentSetId: Long

  // Only some jobs require DocumentCloud credentials
  val documentCloudUsername: Option[String]
  val documentCloudPassword: Option[String]

  var state: DocumentSetCreationJobState
  var fractionComplete: Double
  var statusDescription: Option[String]

  def observeCancellation(f: PersistentDocumentSetCreationJob => Unit)

  /**
   * Updates state, fractionComplete, and statusDescription
   */
  def update

  /** delete the object from the database */
  def delete
}

/** Factory for loading jobs from the database */
object PersistentDocumentSetCreationJob {
  import org.overviewproject.postgres.SquerylEntrypoint._
  import org.overviewproject.tree.orm.DocumentSetCreationJob
  import persistence.Schema.documentSetCreationJobs

  private type DocumentSetCreationJobData = (Long, // id
  Long, // documentSetId
  Int, // state
  Double, // fractionComplete
  Option[String], // statusDescription
  Option[String], // documentCloudUserName
  Option[String]) // doucmentCloudPassword

  /** Find all jobs in the specified state */
  def findJobsWithState(state: DocumentSetCreationJobState): List[PersistentDocumentSetCreationJob] = {

    val jobs = from(documentSetCreationJobs)(d => where(d.state === state) select (d))
    jobs.map(new PersistentDocumentSetCreationJobImpl(_)).toList
  }
  
  /** Find first job, ordered by id, in the specified state */
  def findFirstJobWithState(state: DocumentSetCreationJobState): Option[PersistentDocumentSetCreationJob] = {
    val job = from(documentSetCreationJobs)(d => where(d.state === state) select(d) orderBy(d.id)).page(0, 1).headOption

    job.map(new PersistentDocumentSetCreationJobImpl(_))
  }

  private class PersistentDocumentSetCreationJobImpl(documentSetCreationJob: DocumentSetCreationJob)
    extends PersistentDocumentSetCreationJob {
    val documentSetId: Long = documentSetCreationJob.documentSetId
    val documentCloudUsername: Option[String] = documentSetCreationJob.documentcloudUsername
    val documentCloudPassword: Option[String] = documentSetCreationJob.documentcloudPassword

    var state: DocumentSetCreationJobState = documentSetCreationJob.state
    var fractionComplete: Double = documentSetCreationJob.fractionComplete
    var statusDescription: Option[String] = Some(documentSetCreationJob.statusDescription)

    private var cancellationObserver: Option[PersistentDocumentSetCreationJob => Unit] = None

    /** Register a callback for notification if job is cancelled when delete is called  */
    def observeCancellation(f: PersistentDocumentSetCreationJob => Unit) { cancellationObserver = Some(f) }

    /**
     * Updates state, fractionComplete, and statusDescription
     * Does not change the state if job is cancelled
     * @return 1 on success, 0 otherwise
     */
    def update {
      val job = documentSetCreationJobs.lookup(documentSetCreationJob.id)

      job.map { j =>
        if (j.state == Cancelled) state = Cancelled
          val updatedJob = org.overviewproject.postgres.SquerylEntrypoint.update(documentSetCreationJobs)(d =>
            where(d.id === documentSetCreationJob.id)
              set (d.documentSetId := documentSetId,
                d.state := state.inhibitWhen(d.state == Cancelled),
                d.fractionComplete := fractionComplete,
                d.statusDescription := statusDescription.getOrElse("")))
      }

    }

    /** @return 1 on successful deletion, 0 otherwise */
    def delete {
      val lockedJob = from(documentSetCreationJobs)(dscj =>
        where(dscj.id === documentSetCreationJob.id) select (dscj)).forUpdate.singleOption

      lockedJob.map { j =>
        if (j.state == Cancelled) cancellationObserver.map { notify => notify(this) }
        else documentSetCreationJobs.delete(j.id)
      }
    }
  }
}
