package org.overviewproject.jobhandler.filegroup

import akka.actor._
import org.overviewproject.jobhandler.MessageQueueActorProtocol.StartListening
import org.overviewproject.tree.orm.DocumentSetCreationJobState
import org.overviewproject.tree.orm.FileGroup
import org.overviewproject.tree.orm.DocumentSet
import org.overviewproject.tree.orm.DocumentSetCreationJob
import org.overviewproject.tree.DocumentSetCreationJobType.FileUpload
import org.overviewproject.tree.orm.DocumentSetCreationJobState.{ NotStarted, Preparing }
import org.overviewproject.tree.orm.FileJobState._
import org.overviewproject.jobhandler.JobDone
import org.overviewproject.database.orm.finders.FileFinder
import org.overviewproject.database.orm.finders.FileGroupFinder
import org.overviewproject.database.orm.finders.FileUploadFinder
import org.overviewproject.database.orm.stores.DocumentSetStore
import org.overviewproject.database.orm.stores.DocumentSetCreationJobStore
import org.overviewproject.database.orm.finders.DocumentSetCreationJobFinder
import org.overviewproject.database.Database
import org.overviewproject.database.orm.stores.DocumentSetUserStore
import org.overviewproject.tree.orm.DocumentSetUser
import org.overviewproject.tree.Ownership

object MotherWorkerProtocol {
  sealed trait Command
  case class StartClusteringCommand(
    fileGroupId: Long,
    title: String,
    lang: String,
    suppliedStopWords: String) extends Command
}

trait FileGroupJobHandlerComponent {
  def createFileGroupJobHandler: Props
  val storage: Storage

  trait Storage {
    def findFileGroup(fileGroupId: Long): Option[FileGroup]
    def countFileUploads(fileGroupId: Long): Long
    def countProcessedFiles(fileGroupId: Long): Long
    def findDocumentSetCreationJobByFileGroupId(fileGroupId: Long): Option[DocumentSetCreationJob]

    def storeDocumentSet(title: String, lang: String, suppliedStopWords: String): Long
    def storeDocumentSetUser(documentSetId: Long, userEmail: String): Unit
    def storeDocumentSetCreationJob(documentSetId: Long, fileGroupId: Long, state: DocumentSetCreationJobState.Value, lang: String, suppliedStopWords: String): Long
    def submitDocumentSetCreationJob(documentSetCreationJob: DocumentSetCreationJob): DocumentSetCreationJob
  }
}

class MotherWorker extends Actor {
  this: FileGroupJobHandlerComponent =>

  import MotherWorkerProtocol._

  private val NumberOfDaughters = 2

  private val fileGroupJobHandlers: Seq[ActorRef] = for (i <- 1 to NumberOfDaughters) yield {
    val handler = context.actorOf(createFileGroupJobHandler)
    handler ! StartListening

    handler
  }

  def receive = {
    case StartClusteringCommand(fileGroupId, title, lang, suppliedStopWords) =>
      storage.findFileGroup(fileGroupId).map { fileGroup =>
        val documentSetId = storage.storeDocumentSet(title, lang, suppliedStopWords)
        storage.storeDocumentSetUser(documentSetId, fileGroup.userEmail)
        val jobState = computeJobState(fileGroup)
        storage.storeDocumentSetCreationJob(documentSetId, fileGroupId, jobState, lang, suppliedStopWords)

        context.parent ! JobDone(fileGroupId)
      }
    case JobDone(fileGroupId) => storage.findDocumentSetCreationJobByFileGroupId(fileGroupId).map { job =>
      if (fileProcessingComplete(fileGroupId)) storage.submitDocumentSetCreationJob(job)

      context.parent ! JobDone(fileGroupId)
    }

  }

  /**
   * If all files have been uploaded, and all uploaded files have been processed,
   * the documentSetCreationJob state is `NotStarted` (ready for clustering). Otherwise the state
   * is `Preparing`
   */
  private def computeJobState(fileGroup: FileGroup): DocumentSetCreationJobState.Value =
    if ((fileGroup.state == Complete) && fileProcessingComplete(fileGroup.id)) NotStarted
    else Preparing

  /** file processing is complete when number of uploads matches number of processed files */
  private def fileProcessingComplete(fileGroupId: Long): Boolean =
    storage.countFileUploads(fileGroupId) == storage.countProcessedFiles(fileGroupId)
}

object MotherWorker {
  private class MotherWorkerImpl extends MotherWorker with FileGroupJobHandlerComponent {
    override def createFileGroupJobHandler: Props = FileGroupJobHandler()

    override val storage: StorageImpl = new StorageImpl

    class StorageImpl extends Storage {
      def findFileGroup(fileGroupId: Long): Option[FileGroup] = Database.inTransaction {
        FileGroupFinder.byId(fileGroupId).headOption
      }

      def countFileUploads(fileGroupId: Long): Long = Database.inTransaction {
        FileUploadFinder.countsByFileGroup(fileGroupId)
      }

      def countProcessedFiles(fileGroupId: Long): Long = Database.inTransaction {
        FileFinder.byFinishedState(fileGroupId).count
      }

      def findDocumentSetCreationJobByFileGroupId(fileGroupId: Long): Option[DocumentSetCreationJob] =
        Database.inTransaction {
          DocumentSetCreationJobFinder.byFileGroupId(fileGroupId).headOption
        }

      def storeDocumentSet(title: String, lang: String, suppliedStopWords: String): Long = Database.inTransaction {
        val documentSet = DocumentSetStore.insertOrUpdate(DocumentSet(
          title = title,
          lang = lang,
          suppliedStopWords = suppliedStopWords))

        documentSet.id
      }
      
      def storeDocumentSetUser(documentSetId: Long, userEmail: String): Unit = Database.inTransaction {
        DocumentSetUserStore.insertOrUpdate(DocumentSetUser(documentSetId, userEmail, Ownership.Owner))
      }

      def storeDocumentSetCreationJob(documentSetId: Long, fileGroupId: Long, state: DocumentSetCreationJobState.Value, lang: String, suppliedStopWords: String): Long =
        Database.inTransaction {
          val documentSetCreationJob = DocumentSetCreationJobStore.insertOrUpdate(DocumentSetCreationJob(
            documentSetId = documentSetId,
            jobType = FileUpload,
            lang = lang,
            suppliedStopWords = suppliedStopWords,
            fileGroupId = Some(fileGroupId),
            state = state))
          documentSetCreationJob.id
        }

      def submitDocumentSetCreationJob(documentSetCreationJob: DocumentSetCreationJob): DocumentSetCreationJob =
        Database.inTransaction {
          DocumentSetCreationJobStore.insertOrUpdate(documentSetCreationJob.copy(state = NotStarted))
        }

    }
  }

  def apply(): Props = Props[MotherWorkerImpl]
}