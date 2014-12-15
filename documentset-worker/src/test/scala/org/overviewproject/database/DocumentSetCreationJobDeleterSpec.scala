package org.overviewproject.database

import org.overviewproject.test.SlickSpecification
import org.overviewproject.blobstorage.BlobStorage
import org.overviewproject.database.Slick.simple._
import org.overviewproject.test.SlickClientInSession
import org.overviewproject.models.DocumentSetCreationJob
import org.overviewproject.models.DocumentSetCreationJobType._
import org.overviewproject.models.tables.DocumentSetCreationJobs
import org.specs2.mock.Mockito

class DocumentSetCreationJobDeleterSpec extends SlickSpecification with Mockito {

  "DocumentSetCreationJobDeleter" should {

    "delete a job" in new JobScope {
      await { deleter.deleteByDocumentSet(documentSet.id) }

      DocumentSetCreationJobs.list must beEmpty
    }

    "delete uploaded csv" in new CsvImportJobScope {
      await { deleter.deleteByDocumentSet(documentSet.id) }
      
      DocumentSetCreationJobs.list must beEmpty
      
      there was one(deleter.mockBlobStorage).delete(s"pglo:$oid")
    }

  }

  trait JobScope extends DbScope {
    val deleter = new TestDocumentSetDeleter

    val documentSet = factory.documentSet()
    val job = createJob

    def createJob: DocumentSetCreationJob =
      factory.documentSetCreationJob(documentSetId = documentSet.id, jobType = Recluster, treeTitle = Some("tree"))
  }

  trait CsvImportJobScope extends JobScope {
    def oid = 1l

    override def createJob =
      factory.documentSetCreationJob(documentSetId = documentSet.id, jobType = CsvUpload, contentsOid = Some(oid))
  }

  class TestDocumentSetDeleter(implicit val session: Session) extends DocumentSetCreationJobDeleter 
  with SlickClientInSession {
    
	override protected val blobStorage = mock[BlobStorage]
	
	def mockBlobStorage = blobStorage
  }

}