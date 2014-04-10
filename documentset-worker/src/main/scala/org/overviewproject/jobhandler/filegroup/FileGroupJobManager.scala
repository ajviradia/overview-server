package org.overviewproject.jobhandler.filegroup

import akka.actor.{ Actor, ActorRef }
import org.overviewproject.jobs.models.ClusterFileGroup

object FileGroupJobQueueProtocol {
  case class CreateDocumentsFromFileGroup(fileGroupId: Long, documentSetId: Long)
  case class FileGroupDocumentsCreated(documentSetId: Long)
}

object ClusteringJobQueueProtocol {
  case class ClusterDocumentSet(documentSetId: Long)
}

trait FileGroupJobManager extends Actor {
  import FileGroupJobQueueProtocol._
  import ClusteringJobQueueProtocol._

  protected val fileGroupJobQueue: ActorRef
  protected val clusteringJobQueue: ActorRef
  
  protected val storage: Storage

  trait Storage {
    def createDocumentSetWithJob(fileGroupId: Long, lang: String,
                                 suppliedStopWords: String, importantWords: String): Long

  }

  def receive = {

    case ClusterFileGroup(fileGroupId, name, lang, stopWords, importantWords) => {
      val documentSetId = storage.createDocumentSetWithJob(fileGroupId, lang, stopWords, importantWords)

      fileGroupJobQueue ! CreateDocumentsFromFileGroup(fileGroupId, documentSetId)
    }
    
    case FileGroupDocumentsCreated(documentSetId) =>
      clusteringJobQueue ! ClusterDocumentSet(documentSetId)

  }

}