package models

import helpers.DbSetup._
import helpers.DbTestContext
import org.specs2.mock._
import org.specs2.mutable.Before
import org.specs2.mutable.Specification
import org.specs2.specification.Scope
import play.api.Play.{ start, stop }
import play.api.test.FakeApplication

  
class PersistentTagSpec extends Specification with Mockito {


  step(start(FakeApplication()))
  
  "PersistentTag" should {

    trait MockComponents extends DbTestContext {
      val loader = mock[PersistentTagLoader]
      val parser = mock[DocumentListParser]
      val saver = mock[PersistentTagSaver]
      val name = "a tag"
      lazy val documentSetId = insertDocumentSet("PersistentTagSpec")

    }

    trait NoTag extends MockComponents  {
      override def setupWithDb = loader loadByName (documentSetId, name) returns None
    }

    trait ExistingTag extends MockComponents  {
      var tagId: Long = _
      
      override def setupWithDb = {
	tagId = insertTag(documentSetId, name)
      }
    }

    trait DocumentsTagged extends ExistingTag  {
      val dummyTagId = 23l
      val documentIds = Seq(1l, 2l)
      val tag = core.Tag(dummyTagId, name, None, core.DocumentIdList(documentIds, 3))

      override def setupWithDb = {
	super.setupWithDb
	val dummyTagData = null
	loader loadTag(tagId) returns dummyTagData
	parser createTags(dummyTagData) returns Seq(tag)
      }
    }

    "create tag if not in database" in new NoTag {
      val tag = PersistentTag.findOrCreateByName(name, documentSetId, loader, parser, saver)
      tag.id must not be equalTo(0)
    }

    "be loaded by findOrCreateByName factory method if in database" in new ExistingTag {
      val tag = PersistentTag.findOrCreateByName(name, documentSetId, loader, parser, saver)

      tag.id must be equalTo (tagId)

    }

    "be loaded by findByName if in database" in new ExistingTag {
      val tag = PersistentTag.findByName(name, documentSetId, loader, parser, saver)

      tag must beSome
      tag.get.id must be equalTo (tagId)
    }

    "return None from findByName if tag is not in database" in new NoTag {
      val tag = PersistentTag.findByName(name, documentSetId, loader, parser, saver)

      tag must beNone
    }

    "should ask loader for number of documents with tag" in new ExistingTag {
      val dummyCount = 454
      loader countDocuments (tagId) returns dummyCount

      val tag = PersistentTag.findOrCreateByName(name, documentSetId, loader, parser, saver)
      val count = tag.count

      there was one(loader).countDocuments(tagId)

      count must be equalTo (dummyCount)
    }

    "should ask loader for number of documents with tag per node" in new ExistingTag {
      val dummyCounts = Seq((1l, 5l), (2l, 3345l))
      val nodeIds = Seq(1l, 2l)

      loader countsPerNode (nodeIds, tagId) returns dummyCounts

      val tag = PersistentTag.findOrCreateByName(name, documentSetId, loader, parser, saver)
      val counts = tag.countsPerNode(nodeIds)

      there was one(loader).countsPerNode(nodeIds, tagId)

      counts must be equalTo (dummyCounts)
    }

    "ask loader and parser to create tag" in new ExistingTag {

      val tagData = Seq((tagId, name, 0l, None, None))
      val dummyTag = core.Tag(tagId, name, None, core.DocumentIdList(Nil, 0))

      loader loadTag (tagId) returns tagData
      parser createTags (tagData) returns Seq(dummyTag)

      val persistentTag = PersistentTag.findOrCreateByName(name, documentSetId, loader, parser, saver)

      val tag = persistentTag.loadTag

      there was one(loader).loadTag(tagId)
      there was one(parser).createTags(tagData)

      tag must be equalTo (dummyTag)
    }

    "load documents referenced by tag" in new DocumentsTagged {
      val persistentTag = PersistentTag.findOrCreateByName(name, documentSetId, loader, parser, saver)

      val documents = persistentTag.loadDocuments

      there was one(loader).loadDocuments(documentIds)
    }

    "delete the tag" in new ExistingTag {
      val rowsDeleted = 10
      saver delete (tagId) returns rowsDeleted

      val tag = PersistentTag.findOrCreateByName(name, documentSetId, loader, parser, saver)

      val count = tag.delete()

      count must be equalTo (rowsDeleted)

      there was one(saver).delete(tagId)
    }

    "update the tag" in new ExistingTag {
      val newName = "new name"
      val newColor = "new color"

      val tag = PersistentTag.findOrCreateByName(name, documentSetId, loader, parser, saver)

      tag.update(newName, newColor)

      there was one(saver).update(tagId, newName, newColor)
    }
  }

  step(stop)
}

