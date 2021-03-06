package org.overviewproject.models.tables

import org.overviewproject.database.Slick.simple._
import org.overviewproject.models.UploadedFile
import java.sql.Timestamp

class UploadedFilesImpl(tag: Tag) extends Table[UploadedFile](tag, "uploaded_file") {

  def id = column[Long]("id", O.PrimaryKey)
  def contentDisposition = column[String]("content_disposition")
  def contentType = column[String]("content_type")
  def size = column[Long]("size")
  def uploadedAt = column[Timestamp]("uploaded_at")

  def * = (id, contentDisposition, contentType, size, uploadedAt) <>
    ((UploadedFile.apply _).tupled, UploadedFile.unapply)
}

object UploadedFiles extends TableQuery(new UploadedFilesImpl(_))
