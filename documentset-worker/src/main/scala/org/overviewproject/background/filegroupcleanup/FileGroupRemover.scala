package org.overviewproject.background.filegroupcleanup

import org.overviewproject.database.Slick.simple._
import org.overviewproject.database.{ SlickClient, SlickSessionProvider }
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import org.overviewproject.blobstorage.BlobStorage
import org.overviewproject.models.tables.FileGroups


/**
 * Delete [[FileGroup]]s and associated [[GroupedFileUpload]].
 * 
 * Ignores `deleted` flag and assumes caller wants to delete [[FileGroup]] no
 * matter what.
 */
trait FileGroupRemover extends SlickClient {

  def remove(fileGroupId: Long): Future[Unit] =
    for {
      g <- groupedFileUploadRemover.removeFileGroupUploads(fileGroupId)
      f <- deleteFileGroup(fileGroupId)
    } yield ()

  private def deleteFileGroup(fileGroupId: Long): Future[Unit] = db { implicit session =>
    FileGroups.filter(_.id === fileGroupId).delete
  }

  protected val groupedFileUploadRemover: GroupedFileUploadRemover
  protected val blobStorage: BlobStorage
}

object FileGroupRemover {
  def apply(): FileGroupRemover = new FileGroupRemoverImpl
  
  private class FileGroupRemoverImpl extends FileGroupRemover with SlickSessionProvider {
    override protected val groupedFileUploadRemover = GroupedFileUploadRemover()
    override protected val blobStorage = BlobStorage
  }
}