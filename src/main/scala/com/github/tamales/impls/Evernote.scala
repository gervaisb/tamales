package com.github.tamales.impls

import java.net.URI

import akka.actor.{Actor, ActorLogging, PoisonPill, Props}
import com.evernote.auth.{EvernoteAuth, EvernoteService}
import com.evernote.clients.{ClientFactory, NoteStoreClient}
import com.evernote.edam.`type`.{Note, NoteSortOrder}
import com.evernote.edam.notestore.NoteFilter
import com.github.tamales._

import scala.collection.mutable

object Evernote {
  def props(events:TasksEventBus):Props = {
    Props(new Evernote(events))
  }

  private val Todo = "<div><en-todo(?:\\s*)(?:checked=\"(true|false)\")?(?:\\s*)/>([^<]*)</div>".r
  private type Page = (Int, Int)
  private val pageSize = 50
  private val filter = {
    val f = new NoteFilter()
    f.setOrder(NoteSortOrder.UPDATED.getValue)
    f.setAscending(false)
    f.setWords("todo:*")
    f
  }
}

/**
  * Provider from Evernote, it update tasks by adding a link to the published
  * task and tag the note with `Tracked`
  * @param events The tasks event bus to publish `TaskFound` event.
  */
class Evernote(private val events:TasksEventBus) extends Actor with ActorConfig with ActorLogging{
  import Publisher._
  import Evernote._
  import Provider._

  import scala.collection.JavaConverters._

  private val cfg = new Cfg
  private val pending = mutable.Buffer.empty[Task]

  private lazy val factory = {
    new ClientFactory(new EvernoteAuth(cfg.service, cfg.token))
  }
  private lazy val store: NoteStoreClient = factory.createNoteStoreClient()
  private lazy val user = factory.createUserStoreClient().getUser

  override def receive:Receive = {
    case Refresh =>
      log.debug("Refreshing tasks from {}", cfg.service)
      find().foreach { task =>
        log.debug(s"Found ${task.id} from Evernote")
        events.publish(TaskFound(task, self))
        watch(task)

      }
      log.info(s"Refresh done, waiting response to ${pending.size} event(s).")
      terminateIfDone()
    case TaskSaved(task, Some(uri)) =>
      update(task, uri)
      forgot(task)
      terminateIfDone()
  }

  private def watch(task: Task) = {
    pending += task
  }

  private def forgot(task: Task) = {
    pending -= task
  }

  private def terminateIfDone() = if ( pending.isEmpty ) {
    self ! PoisonPill
  }


  private def find():Seq[Task] = pages.flatMap(notesIn).flatMap(parse).
    filterNot(isComplete)

  private def isComplete(task:Task):Boolean = task.done match {
    case Some(true) => true
    case _ => false
  }

  private def pages:Seq[Page] = {
    val total = store.findNoteCounts(filter, false).getNotebookCounts.asScala.foldLeft(0)(_ + _._2)
    for (offset <- (0 to total).by(pageSize)) yield (offset, pageSize)
  }

  private def notesIn(page: Page):Seq[Note] = {
    store.findNotes(filter, page._1, page._2).getNotes.asScala.map { note =>
      store.getNote(note.getGuid, true, false, false, false)
    }
  }

  private def parse(note: Note):Seq[Task] = {
    Todo.findAllMatchIn(note.getContent).map { matching =>
      val done = Option(matching.group(1)).map(_.toBoolean).orElse(Some(false))
      val summary = matching.group(2)
      new Task(TaskId(URI.create(s"${cfg.service.getHost}/shard/${user.getShardId}/nl/${user.getId}/${note.getGuid}")), summary, done)
    }.toSeq
  }

  private def update(task: Task, uri: URI) = {
    val note = store.getNote(task.id.self.getPath.split("/").last, true, true, true, true)
    val checked = task.done.filter(_==true).map { _ =>
      " checked=\"true\""
    }.getOrElse("")
    val provider = uri.getHost.split('.').reverse.drop(1).head.capitalize
    val link = s"""<a href="$uri" target="_blank">$provider</a>"""
    val content = note.getContent.replaceAll(
      s"""<div><en-todo(?:\\s*)(?:checked="(true|false)")?(?:\\s*)/>${task.summary}([^<]*)</div>""",
      s"""<div><en-todo$checked/>${task.summary} ($link)</div>""")
    note.setContent(content)
    if ( !note.getTagNames.asScala.contains("Tracked") ) {
      note.addToTagNames("Tracked")
    }
    note.unsetAttributes()
    note.unsetResources()
    store.updateNote(note)
    log.info(s"""Link to $uri added for task "${task.summary}" in ${note.getTitle}.""")
  }

  class Cfg {
    val service:EvernoteService = EvernoteService.valueOf(config.getString("providers.evernote.service").toUpperCase())
    val token:String = config.getString("providers.evernote.token")
  }
}
