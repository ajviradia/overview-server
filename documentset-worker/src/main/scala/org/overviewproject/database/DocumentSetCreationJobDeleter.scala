package org.overviewproject.database

import scala.concurrent.Future
import org.overviewproject.blobstorage.BlobStorage
import org.overviewproject.database.Slick.simple._
import org.overviewproject.models.tables.DocumentSetCreationJobs


trait DocumentSetCreationJobDeleter extends SlickClient {

  def deleteByDocumentSet(documentSetId: Long): Future[Unit] = db { implicit session =>
    val documentSetCreationJob = DocumentSetCreationJobs.filter(_.documentSetId === documentSetId)
    
    val uploadedCsvOids = documentSetCreationJob.map(_.contentsOid).list.flatten
    
    uploadedCsvOids.map { oid =>
      val csvUploadLocation = s"pglo:$oid"
      
      blobStorage.delete(csvUploadLocation)
    }
    
    documentSetCreationJob.delete
  }
  
  protected val blobStorage: BlobStorage
}

