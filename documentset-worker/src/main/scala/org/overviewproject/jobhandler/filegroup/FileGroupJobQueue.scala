package org.overviewproject.jobhandler.filegroup

import scala.collection.mutable
import akka.actor.Actor
import akka.actor.ActorRef
import org.overviewproject.database.Database
import org.overviewproject.database.orm.finders.GroupedFileUploadFinder
import akka.actor.Props
import org.overviewproject.util.Logger
import org.overviewproject.jobhandler.filegroup.ProgressReporterProtocol._
import akka.actor.Terminated

object FileGroupJobQueueProtocol {
  case class CreateDocumentsFromFileGroup(documentSetId: Long, fileGroupId: Long)
  case class FileGroupDocumentsCreated(documentSetId: Long)
  case class CancelFileUpload(documentSetId: Long, fileGroupId: Long)
  case class DeleteFileUpload(documentSetId: Long, fileGroupId: Long)
  case class FileUploadDeleted(documentSetId: Long, fileGroupId: Long)
}

/**
 * FileGroupJobQueue manages FileGroup related jobs.
 *  - CreateDocumentsFromFileGroup jobs are split into tasks for each uploaded file in the file group. Each task results
 *  the uploaded file being converted into a file and pages, which are later used to create documents.
 *  - DeleteFileUpload jobs are one task that delete all entries in the database related to a cancelled file upload job
 *  - CancelFileUpload results in a CancelTask message sent to workers processing uploads in the specified file group.
 *  The JobQueue doesn't distinguish between cancelled and completed task (because a successful task completion message
 *  can be received after the cancellation message has been received). The JobQueue expects only one response from any
 *  worker working on a task. Once all tasks have been completed or cancelled, the requester is notified that the job
 *  is complete.
 *  A CancelUpload message may be received for an unknown job during restart and recovery from an unexpected shutdown. In
 *  this case, the JobQueue responds as if the job has been successfully cancelled.
 *
 *  DeleteFileUpload jobs cannot be cancelled (they probably don't even belong here).
 *
 *  The FileGroupJobQueue waits for workers to register. As tasks become available, workers are notified. Idle workers
 *  respond, and are handed tasks. Workers are notified when they register, and when new tasks are added to the task queue.
 *  Workers should signal that they are ready to accept tasks when they are idle (after completing a task).
 *
 */
trait FileGroupJobQueue extends Actor {
  import FileGroupJobQueueProtocol._
  import org.overviewproject.jobhandler.filegroup.task.FileGroupTaskWorkerProtocol._

  type DocumentSetId = Long

  protected val storage: Storage

  trait Storage {
    def uploadedFileIds(fileGroupId: Long): Set[Long]
  }

  protected val progressReporter: ActorRef

  private case class JobRequest(requester: ActorRef)

  private val workerPool: mutable.Set[ActorRef] = mutable.Set.empty
  private val taskQueue: mutable.Queue[TaskWorkerTask] = mutable.Queue.empty
  private val jobTasks: mutable.Map[DocumentSetId, Set[Long]] = mutable.Map.empty
  private val jobRequests: mutable.Map[DocumentSetId, JobRequest] = mutable.Map.empty
  private val busyWorkers: mutable.Map[ActorRef, TaskWorkerTask] = mutable.Map.empty

  def receive = {
    case RegisterWorker(worker) => {
      Logger.info(s"Registering worker ${worker.path.toString}")
      context.watch(worker)
      workerPool += worker
      if (!taskQueue.isEmpty) worker ! TaskAvailable

    }
    case CreateDocumentsFromFileGroup(documentSetId, fileGroupId) => {
      Logger.info(s"Extract text task for FileGroup [$fileGroupId]")
      if (isNewRequest(documentSetId)) {
        val fileIds = uploadedFilesInFileGroup(fileGroupId)

        progressReporter ! StartJob(documentSetId, fileIds.size)

        addNewTasksToQueue(documentSetId, fileGroupId, fileIds)
        jobRequests += (documentSetId -> JobRequest(sender))

        notifyWorkers
      }
    }
    case ReadyForTask => {
      if (workerIsFree(sender) && !taskQueue.isEmpty) {
        taskQueue.dequeue match {
          case task @ CreatePagesTask(documentSetId, fileGroupId, uploadedFileId) => {
            Logger.info(s"($documentSetId:$fileGroupId) Sending task $uploadedFileId to ${sender.path.toString}")
            progressReporter ! StartTask(documentSetId, uploadedFileId)
            sender ! task
            busyWorkers += (sender -> task)
          }
          case task @ DeleteFileUploadJob(documentSetId, fileGroupId) => {
            Logger.info(s"($documentSetId:$fileGroupId) Sending delete job to ${sender.path.toString}")
            sender ! task
            busyWorkers += (sender -> task)
          }
        }
      }
    }
    case CreatePagesTaskDone(documentSetId, uploadedFileId, outputFileId) => {
      Logger.info(s"($documentSetId) Task ${uploadedFileId} Done")
      progressReporter ! CompleteTask(documentSetId, uploadedFileId)
      busyWorkers -= sender

      whenTaskIsComplete(documentSetId, uploadedFileId) {
        notifyRequesterIfJobIsDone
      }
    }
    case CancelFileUpload(documentSetId, fileGroupId) => {
      Logger.info(s"($documentSetId:$fileGroupId) Cancelling Extract text tasks")
      jobRequests.get(documentSetId).fold {
        sender ! FileGroupDocumentsCreated(documentSetId)
      } { r =>
        busyWorkersWithTask(documentSetId).foreach { _ ! CancelTask }
        removeTasksInQueue(documentSetId)
      }
    }
    case DeleteFileUpload(documentSetId, fileGroupId) => {
      Logger.info(s"($documentSetId:$fileGroupId) Deleting upload job")
      taskQueue += DeleteFileUploadJob(documentSetId, fileGroupId)
      jobRequests += (documentSetId -> JobRequest(sender))

      notifyWorkers
    }
    case DeleteFileUploadJobDone(documentSetId, fileGroupId) => {
      Logger.info(s"($documentSetId:$fileGroupId) Deleted upload job")
      busyWorkers -= sender
      jobRequests.get(documentSetId).map { r =>
        r.requester ! FileUploadDeleted(documentSetId, fileGroupId)
        jobRequests -= documentSetId
      }
    }
    case Terminated(worker) => {
      Logger.info(s"Removing ${worker.path.toString} from worker pool")
      workerPool -= worker
      busyWorkers.get(worker).map { task => 
        busyWorkers -= worker
        taskQueue += task
      }
      
      notifyWorkers
    }

  }
  
  private def notifyWorkers: Unit = workerPool.filter(workerIsFree).map { _ ! TaskAvailable }

  private def workerIsFree(worker: ActorRef): Boolean = busyWorkers.get(worker).isEmpty
  
  private def isNewRequest(documentSetId: Long): Boolean = !jobRequests.contains(documentSetId)

  private def uploadedFilesInFileGroup(fileGroupId: Long): Set[Long] = storage.uploadedFileIds(fileGroupId)

  private def addNewTasksToQueue(documentSetId: Long, fileGroupId: Long, uploadedFileIds: Set[Long]): Unit = {
    val newTasks = uploadedFileIds.map(CreatePagesTask(documentSetId, fileGroupId, _))
    taskQueue ++= newTasks
    jobTasks += (documentSetId -> uploadedFileIds)
  }

  private def whenTaskIsComplete(documentSetId: Long, uploadedFileId: Long)(f: (JobRequest, Long, Set[Long]) => Unit) =
    for {
      tasks <- jobTasks.get(documentSetId)
      request <- jobRequests.get(documentSetId)
      remainingTasks = tasks - uploadedFileId
    } f(request, documentSetId, remainingTasks)

  private def notifyRequesterIfJobIsDone(request: JobRequest, documentSetId: Long, remainingTasks: Set[Long]): Unit =
    if (remainingTasks.isEmpty) {
      jobTasks -= documentSetId
      jobRequests -= documentSetId

      progressReporter ! CompleteJob(documentSetId)
      request.requester ! FileGroupDocumentsCreated(documentSetId)
    } else {
      jobTasks += (documentSetId -> remainingTasks)
    }

  private def busyWorkersWithTask(documentSetId: Long): Iterable[ActorRef] =
    for {
      (worker, task) <- busyWorkers if task.documentSetId == documentSetId
    } yield worker

  private def removeTasksInQueue(documentSetId: Long): Unit = {
    val notStarted = taskQueue.dequeueAll(_.documentSetId == documentSetId)
    val notStartedUploadIds = notStarted.collect {
      case CreatePagesTask(_, _, uploadedFileId) => uploadedFileId
    }

    for (tasks <- jobTasks.get(documentSetId)) {
      jobTasks += (documentSetId -> tasks.diff(notStartedUploadIds.toSet))
    }

  }
}

class FileGroupJobQueueImpl(progressReporterActor: ActorRef) extends FileGroupJobQueue {
  class DatabaseStorage extends Storage {
    override def uploadedFileIds(fileGroupId: Long): Set[Long] = Database.inTransaction {
      GroupedFileUploadFinder.byFileGroup(fileGroupId).toIds.toSet
    }
  }

  override protected val storage: Storage = new DatabaseStorage
  override protected val progressReporter: ActorRef = progressReporterActor

}

object FileGroupJobQueue {
  def apply(progressReporter: ActorRef): Props = Props(new FileGroupJobQueueImpl(progressReporter))
}