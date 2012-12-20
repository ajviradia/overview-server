package persistence

import java.io.InputStream
import org.overviewproject.database.Database
import org.overviewproject.postgres.LO
import org.overviewproject.database.DB

class LargeObjectInputStream(oid: Long, bufferSize: Int = 8012) extends InputStream {

  private val buffer = new Array[Byte](bufferSize)
  private var largeObjectPosition: Int = 0
  private var bufferPosition: Int = bufferSize
  private var bufferEnd: Int = bufferSize

  def read(): Int = {
    refreshBuffer()
    readNextFromBuffer()
  }

  private def readNextFromBuffer(): Int = {
    val b =
      if (bufferPosition < bufferEnd) buffer(bufferPosition)
      else -1

    bufferPosition += 1

    b
  }

  private def refreshBuffer() {
    if (bufferPosition >= bufferSize) Database.inTransaction {
      implicit val pgc = DB.pgConnection(Database.currentConnection)

      LO.withLargeObject(oid) { largeObject =>
        largeObject.seek(largeObjectPosition)
        val bytesRead = largeObject.read(buffer, 0, bufferSize)
        bufferEnd = bytesRead
        largeObjectPosition += bytesRead
        bufferPosition = 0
      }
    }

  }

}